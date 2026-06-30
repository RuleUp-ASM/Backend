package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.watcher.domain.*;
import com.ruleup.ruleup_backend.watcher.dto.*;
import com.ruleup.ruleup_backend.watcher.infra.*;
import com.ruleup.ruleup_backend.watcher.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 감시자 초대·진입·수락·동의(OTP)·수신거부 (CLAUDE.md §5.9 / §11.4).
 *
 * 합법성 게이트(§5.9):
 *  - 룰업은 감시자에게 직접 발송하지 않는다(초대는 생성자 카톡 공유). OTP만 감시자 본인이 입력한 번호로 발송.
 *  - 비유저 연락처·동의는 감시자 본인이 웹에서 직접 제출. 생성자에겐 원본 연락처 미노출(마스킹).
 *  - 수신거부/차단 이력(생성자–감시자 30일)은 등록(수락/동의/OTP) 시점에 검사.
 */
@Service
@RequiredArgsConstructor
public class WatcherInvitationService {

    private static final int FREE_LIMIT = 3;                         // §6.3 MVP 무료 3명(구독=무제한은 후속)
    private static final Duration INVITE_TTL = Duration.ofDays(7);   // 초대 만료
    private static final Duration BLOCK_TTL = Duration.ofDays(30);   // 수신거부 후 재초대 차단
    private static final int OTP_TTL_SEC = 180;
    private static final int OTP_RESEND_SEC = 30;

    private final WatcherInvitationRepository invitationRepository;
    private final WatcherRepository watcherRepository;
    private final WatcherOtpRepository otpRepository;
    private final WatcherBlockRepository blockRepository;
    private final ChallengeQueryService challengeQuery;
    private final UserRepository userRepository;
    private final ContactCipher contactCipher;
    private final SmsSender smsSender;

    @Value("${app.watcher.invite-base-url:https://ruleup.app}")
    private String inviteBaseUrl;

    private final SecureRandom random = new SecureRandom();

    // ===== 초대 생성 (생성자) =====
    @Transactional
    public InvitationCreateResponse createInvitation(UUID ownerId, UUID challengeId) {
        Challenge challenge = loadOwnedChallenge(ownerId, challengeId);

        long live = invitationRepository.countByChallengeIdAndStatusIn(
                challengeId, List.of(InvitationStatus.INVITED, InvitationStatus.CONSENTED));
        if (live >= FREE_LIMIT) throw new BusinessException(ErrorCode.WATCHER_LIMIT_EXCEEDED);

        Instant now = Instant.now();
        String token = Tokens.generate("inv_");
        WatcherInvitation invitation = invitationRepository.save(
                WatcherInvitation.create(ownerId, challengeId, token, now.plus(INVITE_TTL)));
        // 초대 1건당 INVITED 감시자 1행(수락/동의로 채워짐).
        watcherRepository.save(Watcher.invited(invitation.getId(), challengeId, ownerId));

        String inviterNickname = visibleInviterNickname(ownerId);
        var kakao = new InvitationCreateResponse.KakaoShare(
                inviterNickname + "님이 당신을 루틴 감시자로 초대했어요",
                "[" + challenge.getTitle() + "]에서 " + inviterNickname
                        + "님이 약속을 지키는지 지켜봐 주세요. 실패하면 알림이 가요.",
                "수락하기");

        return new InvitationCreateResponse(
                invitation.getId().toString(), token, inviteUrl(token),
                invitation.getStatus().name(), invitation.getExpiresAt().toString(), kakao);
    }

    // ===== 초대 진입 (공개) =====
    @Transactional
    public InvitationEntryResponse getByToken(String token, UUID viewerUserId) {
        WatcherInvitation invitation = loadInvitation(token);
        Instant now = Instant.now();
        if (invitation.isExpired(now)) {
            if (invitation.getStatus() == InvitationStatus.INVITED) invitation.markExpired();
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        Challenge challenge = challengeQuery.findChallenge(invitation.getChallengeId()).orElse(null);
        String challengeTitle = (challenge != null) ? challenge.getTitle() : null;
        String inviterNickname = visibleInviterNickname(invitation.getInviterUserId());

        // 차단은 USER 뷰어만 진입 시점에 판정 가능(비유저는 번호 입력 전이라 OTP/동의 시 검사).
        boolean blocked = false;
        String blockedUntil = null;
        if (viewerUserId != null) {
            String subjectKey = WatcherHashes.subjectKeyForUser(viewerUserId);
            var b = blockRepository.findTopByInviterUserIdAndSubjectKeyAndBlockedUntilAfterOrderByBlockedUntilDesc(
                    invitation.getInviterUserId(), subjectKey, now).orElse(null);
            if (b != null) { blocked = true; blockedUntil = b.getBlockedUntil().toString(); }
        }

        return new InvitationEntryResponse(
                invitation.getId().toString(), invitation.getStatus().name(),
                challengeTitle, inviterNickname,
                viewerUserId != null, blocked, blockedUntil,
                invitation.getExpiresAt().toString());
    }

    // ===== 유저 수락(= 동의, 인앱) =====
    @Transactional
    public WatcherAcceptResponse accept(String token, UUID userId) {
        WatcherInvitation invitation = loadInvitation(token);
        Instant now = Instant.now();
        if (invitation.isExpired(now)) {
            if (invitation.getStatus() == InvitationStatus.INVITED) invitation.markExpired();
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (invitation.getStatus() == InvitationStatus.REVOKED)
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        if (invitation.isConsumed())
            throw new BusinessException(ErrorCode.ALREADY_CONSENTED);

        ensureNotBlocked(invitation.getInviterUserId(), WatcherHashes.subjectKeyForUser(userId), now);

        Watcher watcher = loadWatcher(invitation.getId());
        boolean challengeActive = isChallengeActive(invitation.getChallengeId());
        watcher.consentAsUser(userId, visibleInviterNickname(userId), challengeActive, now);
        invitation.markConsented();

        return new WatcherAcceptResponse(
                watcher.getId().toString(), watcher.getStatus().name(), WatcherChannel.IN_APP.name());
    }

    // ===== 비유저 OTP 발송 =====
    @Transactional
    public OtpSendResponse sendOtp(String token, String phone) {
        WatcherInvitation invitation = loadInvitation(token);
        Instant now = Instant.now();
        if (invitation.isExpired(now)) {
            if (invitation.getStatus() == InvitationStatus.INVITED) invitation.markExpired();
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (!PhoneNumbers.isValid(phone)) throw new BusinessException(ErrorCode.INVALID_PHONE);
        String normalized = PhoneNumbers.normalize(phone);

        ensureNotBlocked(invitation.getInviterUserId(), WatcherHashes.subjectKeyForPhone(normalized), now);

        // 재발송 쿨다운(직전 OTP의 resendAvailableAt).
        otpRepository.findTopByInvitationIdOrderByCreatedAtDesc(invitation.getId()).ifPresent(prev -> {
            if (now.isBefore(prev.getResendAvailableAt()))
                throw new BusinessException(ErrorCode.OTP_RESEND_LIMITED);
        });

        String code = String.format("%06d", random.nextInt(1_000_000));
        WatcherOtp otp = otpRepository.save(WatcherOtp.issue(
                invitation.getId(),
                contactCipher.encrypt(normalized),
                WatcherHashes.subjectKeyForPhone(normalized),
                WatcherHashes.sha256Hex(code),
                now.plusSeconds(OTP_TTL_SEC),
                now.plusSeconds(OTP_RESEND_SEC)));

        // 야간 디퍼 예외 — 즉시 발송. 감시자 본인이 입력한 번호로만(§5.9).
        smsSender.sendOtp(normalized, code);

        return new OtpSendResponse(otp.getId().toString(), OTP_TTL_SEC, OTP_RESEND_SEC);
    }

    // ===== 비유저 동의(OTP 검증 + 연락처·수신동의 저장) =====
    @Transactional
    public WatcherConsentResponse consent(String token, UUID otpId, String otpCode, boolean consent) {
        WatcherInvitation invitation = loadInvitation(token);
        Instant now = Instant.now();
        if (invitation.isExpired(now)) {
            if (invitation.getStatus() == InvitationStatus.INVITED) invitation.markExpired();
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (!consent) throw new BusinessException(ErrorCode.CONSENT_REQUIRED);

        WatcherOtp otp = (otpId != null) ? otpRepository.findById(otpId).orElse(null) : null;
        if (otp == null || !otp.getInvitationId().equals(invitation.getId()) || otp.isConsumed())
            throw new BusinessException(ErrorCode.OTP_INVALID);
        if (otp.isExpired(now)) throw new BusinessException(ErrorCode.OTP_EXPIRED);
        if (otpCode == null || !otp.matches(WatcherHashes.sha256Hex(otpCode)))
            throw new BusinessException(ErrorCode.OTP_INVALID);

        String phone = contactCipher.decrypt(otp.getPhoneEnc());
        ensureNotBlocked(invitation.getInviterUserId(), WatcherHashes.subjectKeyForPhone(phone), now);
        if (invitation.isConsumed()) throw new BusinessException(ErrorCode.ALREADY_CONSENTED);

        Watcher watcher = loadWatcher(invitation.getId());
        boolean challengeActive = isChallengeActive(invitation.getChallengeId());
        watcher.consentAsNonUser(
                otp.getPhoneEnc(), PhoneNumbers.mask(phone), Tokens.generate("unsub_"),
                challengeActive, now);
        otp.consume(now);
        invitation.markConsented();

        return new WatcherConsentResponse(
                watcher.getId().toString(), watcher.getStatus().name(),
                WatcherChannel.SMS.name(), watcher.getContactMasked());
    }

    // ===== 수신거부 (공개, SMS 링크의 서명 토큰) =====
    @Transactional
    public WatcherUnsubscribeResponse unsubscribe(String unsubToken) {
        if (unsubToken == null || unsubToken.isBlank())
            throw new BusinessException(ErrorCode.UNSUBSCRIBE_TOKEN_INVALID);
        Watcher watcher = watcherRepository.findByUnsubscribeToken(unsubToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUBSCRIBE_TOKEN_INVALID));

        Instant now = Instant.now();
        Instant reblockUntil = now.plus(BLOCK_TTL);
        // 30일 재초대 차단(생성자–감시자 단위). 연락처는 해시로만 보관.
        if (watcher.getContactEnc() != null) {
            String phone = contactCipher.decrypt(watcher.getContactEnc());
            blockRepository.save(WatcherBlock.of(
                    watcher.getInviterUserId(), WatcherHashes.subjectKeyForPhone(phone), reblockUntil));
        } else if (watcher.getWatcherUserId() != null) {
            blockRepository.save(WatcherBlock.of(
                    watcher.getInviterUserId(), WatcherHashes.subjectKeyForUser(watcher.getWatcherUserId()), reblockUntil));
        }
        watcher.revoke(now);
        invitationRepository.findById(watcher.getInvitationId()).ifPresent(WatcherInvitation::markRevoked);

        return new WatcherUnsubscribeResponse(WatcherStatus.REVOKED.name(), reblockUntil.toString());
    }

    // ===== 내부 헬퍼 =====
    private Challenge loadOwnedChallenge(UUID ownerId, UUID challengeId) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
        return c;
    }

    private WatcherInvitation loadInvitation(String token) {
        return invitationRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
    }

    private Watcher loadWatcher(UUID invitationId) {
        return watcherRepository.findByInvitationId(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
    }

    private void ensureNotBlocked(UUID inviterUserId, String subjectKey, Instant now) {
        if (blockRepository.existsByInviterUserIdAndSubjectKeyAndBlockedUntilAfter(inviterUserId, subjectKey, now))
            throw new BusinessException(ErrorCode.WATCHER_BLOCKED);
    }

    private boolean isChallengeActive(UUID challengeId) {
        return challengeQuery.findChallenge(challengeId)
                .map(c -> c.getStatus() == ChallengeStatus.ACTIVE)
                .orElse(false);
    }

    private String visibleInviterNickname(UUID inviterUserId) {
        return userRepository.findById(inviterUserId)
                .map(u -> u.visibleNicknameTo(null))   // 외부 노출용 공개 닉네임(모더레이션 반영)
                .orElse("회원");
    }

    private String inviteUrl(String token) {
        return inviteBaseUrl + "/w/" + token;
    }
}
