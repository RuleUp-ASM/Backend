package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.watcher.domain.*;
import com.ruleup.ruleup_backend.watcher.dto.InvitationCreateResponse;
import com.ruleup.ruleup_backend.watcher.dto.InvitationEntryResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherAcceptResponse;
import com.ruleup.ruleup_backend.watcher.infra.Tokens;
import com.ruleup.ruleup_backend.watcher.infra.WatcherHashes;
import com.ruleup.ruleup_backend.watcher.repository.WatcherConsentLogRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherInvitationRepository;
import com.ruleup.ruleup_backend.watcher.repository.WatcherRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 초대 발급과 동의 성립 — 패널티 감시자 백엔드 4-2.
 *
 * <p>이 서비스의 전제는 하나다 — <b>룰업은 동의하지 않은 외부인에게 먼저 닿을 수 없다.</b>
 * 그래서 초대를 직접 보내지 않고 토큰과 카드 메타만 내려주며, 전달은 사용자 본인 명의의
 * 카카오톡 공유로 이뤄진다(사적 통신이라 동의가 필요 없다).
 *
 * <p>동의 성립도 서버에서만 판정한다. 클라이언트가 "수락했다"고 보내는 것이 아니라
 * <b>토큰 검증 + 로그인 확인</b>을 모두 통과한 뒤에야 ACTIVE 로 전이하고 그 시각을 남긴다.
 */
@Service
@RequiredArgsConstructor
public class WatcherInvitationService {

    private final WatcherInvitationRepository invitationRepository;
    private final WatcherRelationRepository relationRepository;
    private final WatcherConsentLogRepository consentLogRepository;
    private final ChallengeQueryService challengeQuery;
    private final UserRepository userRepository;

    @Value("${app.watcher.invite-base-url:https://ruleup.app}")
    private String inviteBaseUrl;

    // ===== 초대 발급 =====

    /**
     * 초대 발급. <b>인원 상한이 없다</b> — 구 무료 3명 한도는 폐지됐다.
     *
     * <p>관계 행을 여기서 만들지 않는 이유는 <b>누가 수락할지 모르기 때문</b>이다. 관계는
     * (챌린지, 피감시자, 감시자) 3중 키인데 초대 시점에는 세 번째 값이 없다. 미리 만들면
     * 수락자 없는 PENDING 행이 쌓이고, 그건 발송 대상 조회가 매번 걸러야 하는 잡음이 된다.
     */
    @Transactional
    public InvitationCreateResponse createInvitation(UUID ownerId, UUID challengeId) {
        Challenge challenge = loadOwnedChallenge(ownerId, challengeId);
        Instant now = Instant.now();

        String token = Tokens.generate("inv_");
        WatcherInvitation invitation = invitationRepository.save(
                WatcherInvitation.issue(challengeId, ownerId, WatcherHashes.sha256Hex(token), now));

        String inviterNickname = visibleNickname(ownerId);
        var kakao = new InvitationCreateResponse.KakaoShare(
                inviterNickname + "님이 당신을 루틴 감시자로 초대했어요",
                "[" + challenge.getTitle() + "]에서 " + inviterNickname
                        + "님이 약속을 지키는지 지켜봐 주세요. 실패하면 알림이 가요.",
                "수락하기");

        return new InvitationCreateResponse(
                invitation.getId().toString(), token, inviteUrl(token),
                invitation.getExpiresAt().toString(), kakao);
    }

    // ===== 초대 진입 =====

    /**
     * 초대 카드 조회. 딥링크로 앱이 열렸을 때 "누가 무엇으로 초대했는지"를 보여주기 위한 것이며,
     * <b>이 호출만으로는 어떤 동의도 성립하지 않는다</b>.
     */
    @Transactional(readOnly = true)
    public InvitationEntryResponse getByToken(String token) {
        WatcherInvitation invitation = loadInvitation(token);
        if (invitation.isExpired(Instant.now()))
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);

        Challenge challenge = challengeQuery.findChallenge(invitation.getChallengeId()).orElse(null);
        return new InvitationEntryResponse(
                invitation.getId().toString(),
                (challenge != null) ? challenge.publicTitle() : null,
                visibleNickname(invitation.getInviterUserId()),
                invitation.getExpiresAt().toString(),
                invitation.getAcceptedAt() != null);
    }

    // ===== 수락 =====

    /**
     * 인앱 수락 — <b>동의가 성립하는 유일한 경로</b>다. 웹 수락과 SMS OTP 는 동의 주체 확인이
     * 약해 폐지됐고, 미설치자는 스토어를 거쳐 가입한 뒤 이 경로로 들어온다.
     *
     * <p>재수락은 멱등하지 않고 409 다. 이미 관계가 있는데 또 수락하면 초대가 잘못 공유된
     * 상황이므로, 조용히 성공시키면 사용자가 그걸 알 수 없다.
     */
    @Transactional
    public WatcherAcceptResponse accept(String token, UUID watcherUserId) {
        WatcherInvitation invitation = loadInvitation(token);
        Instant now = Instant.now();

        if (invitation.isExpired(now)) throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        if (invitation.getInviterUserId().equals(watcherUserId))
            throw new BusinessException(ErrorCode.CANNOT_WATCH_SELF);

        relationRepository.findByChallengeIdAndTargetUserIdAndWatcherUserId(
                        invitation.getChallengeId(), invitation.getInviterUserId(), watcherUserId)
                .filter(r -> r.getRemovedAt() == null)
                .ifPresent(r -> { throw new BusinessException(ErrorCode.ALREADY_WATCHER); });

        WatcherRelation relation = relationRepository.save(WatcherRelation.accepted(
                invitation.getChallengeId(), invitation.getInviterUserId(), watcherUserId,
                invitation.getExpiresAt().minus(WatcherInvitation.TTL), now));
        // 동의 시각은 관계와 이력 양쪽에 남긴다 — 관계가 정리돼도 이력은 보존된다.
        consentLogRepository.save(WatcherConsentLog.of(relation.getId(), ConsentEvent.ACCEPTED, now));
        invitation.markAccepted(now);

        Challenge challenge = challengeQuery.findChallenge(invitation.getChallengeId()).orElse(null);
        return new WatcherAcceptResponse(
                relation.getId().toString(),
                relation.getStatus().name(),
                (challenge != null) ? challenge.publicTitle() : null,
                visibleNickname(invitation.getInviterUserId()),
                now.toString());
    }

    // ===== 내부 =====

    private Challenge loadOwnedChallenge(UUID ownerId, UUID challengeId) {
        Challenge c = challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
        return c;
    }

    /** 원본 토큰이 아니라 해시로 찾는다 — 위조·오타는 조회 실패로 떨어져 400 이 된다. */
    private WatcherInvitation loadInvitation(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(ErrorCode.INVITATION_INVALID);
        return invitationRepository.findByTokenHash(WatcherHashes.sha256Hex(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_INVALID));
    }

    private String visibleNickname(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.visibleNicknameTo(null))   // 모더레이션 대체 규칙이 반영된 공개 닉네임
                .orElse("회원");
    }

    private String inviteUrl(String token) {
        return inviteBaseUrl + "/w/" + token;
    }
}
