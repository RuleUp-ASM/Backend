package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.applink.AppLinkType;
import com.ruleup.ruleup_backend.applink.AppLinks;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeInvitation;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.InvitationTokens;
import com.ruleup.ruleup_backend.challenge.domain.MemberRole;
import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.dto.RoomAdminDtos;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeInvitationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.notification.NotificationMuteCleaner;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoomAdminService {
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeInvitationRepository invitationRepository;
    private final UserChallengeCounterRepository counterRepository;
    private final NotificationPublisher notificationPublisher;
    private final NotificationMuteCleaner muteCleaner;
    private final ApplicationEventPublisher eventPublisher;
    private final AppLinks appLinks;

    @Transactional
    public RoomAdminDtos.InvitationResponse invite(UUID ownerId, UUID challengeId) {
        Challenge challenge = locked(challengeId);
        requireOwner(challenge, ownerId);
        if (!challenge.isGroup() || !"PRIVATE".equals(challenge.getVisibility()))
            throw new BusinessException(ErrorCode.NOT_PRIVATE_CHALLENGE);
        String token = InvitationTokens.generate();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(7));
        ChallengeInvitation invitation = invitationRepository.saveAndFlush(
                ChallengeInvitation.create(challengeId, ownerId, InvitationTokens.hash(token), expiresAt));
        return new RoomAdminDtos.InvitationResponse(invitation.getId().toString(), token,
                appLinks.build(AppLinkType.CHALLENGE_INVITATION, token), expiresAt.toString());
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
        // 참여 인원 변화 → version 증가. decrementParticipantCount 는 clearAutomatically 라
        // 그 뒤에서 부르면 challenge 가 준영속이 되어 증가가 조용히 사라진다(반드시 앞에서).
        challenge.bumpVersion();
        challengeRepository.decrementParticipantCount(challengeId);
        counterRepository.decrement(targetUserId);   // 동시 참여 3개 카운터도 함께 정리
        // 나간 방의 음소거는 설정 목록에 남을 이유가 없고, 재입장 시 되살아나면 안 된다.
        muteCleaner.clearMute(targetUserId, challengeId);
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "KICK"));
        // 같은 방에서 재입장 후 다시 강퇴될 수 있으므로 강퇴 시각까지 키에 넣는다.
        notificationPublisher.publish(NotificationEvent.forChallenge(targetUserId,
                NotificationType.CHALLENGE_KICKED, "챌린지에서 내보내졌어요", normalized, challengeId,
                Map.of(NotificationParams.EVENT_KEY,
                        challengeId + ":" + rejoinAt.toEpochMilli())));
        return new RoomAdminDtos.KickResponse(true, targetUserId.toString(), rejoinAt.toString());
    }

    /** 부정행위 검출 강퇴의 {@code kick_reason}. 제재 이력의 {@code reasonCode} 로 그대로 나간다. */
    public static final String CHEAT_KICK_REASON = "CHEAT_DETECTED";

    /**
     * 부정행위 검출 강퇴 — 방 내부 테크 스펙 5-6. 강퇴 3종 중 <b>유일하게 백오프가 아니라 해당 챌린지
     * 영구 차단</b>이다. 판정(이상패턴 탐지 확정)은 인증 모듈 몫이고 여기는 집행만 한다 — 방장을 거치지 않는다.
     *
     * <p>멱등이다 — 같은 신호가 다시 와도 이미 영구 차단이면 아무것도 하지 않는다.
     * 검출 전에 스스로 나간 멤버도 차단 표시는 남긴다 — 탈퇴 1주 대기로 끝나면 치팅 후 먼저 나가는 것이 우회로가 된다.
     */
    @Transactional
    public void kickForCheat(UUID challengeId, UUID targetUserId) {
        // 락 순서는 전 경로에서 사용자 행 → 챌린지 행으로 고정한다(가입·탈퇴와 동일 — 데드락 방지).
        counterRepository.ensureRow(targetUserId);
        counterRepository.lockCount(targetUserId);
        Challenge challenge = locked(challengeId);
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_MEMBER));
        if (target.isRejoinBanned()) return;
        if (!target.isActive()) {
            target.banFromRejoin();
            return;
        }
        Instant now = Instant.now();
        // 방장이 쫓겨나면 탈퇴와 같이 봇방장 체제로 넘어간다 — 방장 없는 방이 되면 안 된다.
        if (target.isOwner()) challenge.convertToBotOwner(now);
        target.kickPermanently(CHEAT_KICK_REASON, now);
        // decrementParticipantCount 는 clearAutomatically 라 bumpVersion 을 반드시 앞에서 부른다.
        challenge.bumpVersion();
        challengeRepository.decrementParticipantCount(challengeId);
        counterRepository.decrement(targetUserId);
        muteCleaner.clearMute(targetUserId, challengeId);
        eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(challengeId, "KICK"));
        // 부정행위는 일반 강퇴와 다른 타입이다 — 진입점이 방이 아니라 제재 이력이고, 유저는
        // 「왜 나갔는지」가 아니라 「무엇으로 판정됐는지」를 봐야 한다. challengeId 를 싣지 않는
        // 이유도 같다: 영구 차단이라 그 방은 「내 챌린지」에 없고 카운터가 뜰 자리가 없다.
        // 영구 차단이라 같은 방에서 두 번 강퇴될 일이 없다 — 방 id 만으로 멱등 키가 된다.
        notificationPublisher.publish(NotificationEvent.of(targetUserId,
                NotificationType.CHEAT_DETECTED, "챌린지에서 내보내졌어요",
                "이 챌린지에는 다시 참여할 수 없어요. 자세한 내용은 제재 이력에서 확인해주세요.",
                Map.of(NotificationParams.EVENT_KEY, challengeId + ":cheat")));
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
        // 방장 승계 통지는 방장 권한 축소로 폐지됐다(알림 정책 2026-08-25). 위임 자체가 Phase 2 다.
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
        Challenge challenge = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (challenge.getDeletedAt() != null) throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        if (challenge.getStatus() == ChallengeStatus.COMPLETED)
            throw new BusinessException(ErrorCode.CHALLENGE_COMPLETED);
        return challenge;
    }

    private void requireOwner(Challenge challenge, UUID userId) {
        if (!challenge.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
    }
}
