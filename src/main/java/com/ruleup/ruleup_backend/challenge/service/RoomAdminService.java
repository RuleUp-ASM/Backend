package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeInvitation;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberRole;
import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.dto.RoomAdminDtos;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeInvitationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationService;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomAdminService {
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeInvitationRepository invitationRepository;
    private final UserChallengeCounterRepository counterRepository;
    private final NotificationService notificationService;

    @Transactional
    public RoomAdminDtos.InvitationResponse invite(UUID ownerId, UUID challengeId) {
        Challenge challenge = locked(challengeId);
        requireOwner(challenge, ownerId);
        if (!challenge.isGroup() || !"PRIVATE".equals(challenge.getVisibility()))
            throw new BusinessException(ErrorCode.NOT_PRIVATE_CHALLENGE);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
        ChallengeInvitation invitation = invitationRepository.saveAndFlush(
                ChallengeInvitation.create(challengeId, ownerId, sha256(token), expiresAt));
        return new RoomAdminDtos.InvitationResponse(invitation.getId().toString(), token,
                "/invite/" + token, expiresAt.toString());
    }

    @Transactional
    public RoomAdminDtos.KickResponse kick(UUID ownerId, UUID challengeId, UUID targetUserId, String reason) {
        // 락 순서는 전 경로에서 사용자 행 → 챌린지 행으로 고정한다(가입·탈퇴와 동일 — 데드락 방지).
        counterRepository.ensureRow(targetUserId);
        counterRepository.lockCount(targetUserId);
        Challenge challenge = locked(challengeId);
        requireOwner(challenge, ownerId);
        if (ownerId.equals(targetUserId)) throw new BusinessException(ErrorCode.CANNOT_KICK_SELF);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 10 || normalized.length() > 500)
            throw new BusinessException(ErrorCode.KICK_REASON_REQUIRED);
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_MEMBER));
        if (target.isOwner()) throw new BusinessException(ErrorCode.CANNOT_KICK_SELF);
        Instant now = Instant.now();
        // 재입장 대기는 1주 → 2주 → 4주 매번 두 배(제재 정책 §4.3). kickCount는 이번 강퇴 반영 전 값.
        Instant rejoinAt = RejoinBackoff.availableAt(now, target.getKickCount());
        target.kick(normalized, now, rejoinAt);
        challengeRepository.decrementParticipantCount(challengeId);
        counterRepository.decrement(targetUserId);   // 동시 참여 3개 카운터도 함께 정리
        challenge.bumpVersion();
        notificationService.notify(targetUserId, NotificationType.CHALLENGE_MEMBER_KICKED,
                "챌린지에서 내보내졌어요", normalized);
        return new RoomAdminDtos.KickResponse(true, targetUserId.toString(), rejoinAt.toString());
    }

    @Transactional
    public RoomAdminDtos.TransferResponse transfer(UUID ownerId, UUID challengeId, UUID targetUserId) {
        Challenge challenge = locked(challengeId);
        requireOwner(challenge, ownerId);
        if (ownerId.equals(targetUserId)) throw new BusinessException(ErrorCode.CANNOT_TRANSFER_TO_SELF);
        ChallengeMember current = memberRepository.findByChallengeIdAndUserId(challengeId, ownerId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_MEMBER));
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_MEMBER));
        current.changeRole(MemberRole.MEMBER);
        target.changeRole(MemberRole.OWNER);
        // 직접 넘겨받은 방장은 3일 면책 대상이 아니다(정책 §11.3) — 경위를 TRANSFER 로 남긴다.
        challenge.transferOwner(targetUserId, Instant.now(), Challenge.GRANT_TRANSFER);
        notificationService.notify(targetUserId, NotificationType.OWNER_TRANSFERRED,
                "방장 권한을 받았어요", challenge.getTitle());
        return new RoomAdminDtos.TransferResponse(targetUserId.toString(), "MEMBER");
    }

    @Transactional
    public RoomAdminDtos.ClaimResponse claim(UUID userId, UUID challengeId) {
        Challenge challenge = locked(challengeId);
        if (!challenge.isBotOwned()) throw new BusinessException(ErrorCode.OWNER_ALREADY_EXISTS);
        ChallengeMember claimant = memberRepository.findByChallengeIdAndUserId(challengeId, userId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER));
        claimant.changeRole(MemberRole.OWNER);
        Instant now = Instant.now();
        // 스스로 손들어 방장이 된 경우만 3일 면책 대상(정책 §11.3).
        challenge.transferOwner(userId, now, Challenge.GRANT_CLAIM);
        return new RoomAdminDtos.ClaimResponse("OWNER",
                now.plus(Challenge.SUCCESSION_GRACE).toString());
    }

    private Challenge locked(UUID challengeId) {
        return challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void requireOwner(Challenge challenge, UUID userId) {
        if (!challenge.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private byte[] sha256(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
