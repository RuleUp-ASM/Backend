package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.DelegationActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.DelegationResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeDelegationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 방장 위임 (생성 및 라이프사이클 스펙 §7-2).
 *  - 요청(OWNER): 대상은 MANAGER만. 챌린지당 유효(PENDING) 요청 1건. 생성 +7일 만료.
 *  - 응답: ACCEPT(대상자, role swap) / REJECT(대상자) / CANCEL(요청 OWNER). 만료 요청은 410.
 * ACCEPT 는 트랜잭션으로 role swap — OWNER 는 항상 정확히 1명(불변식).
 */
@Service
@RequiredArgsConstructor
public class ChallengeDelegationService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final ChallengeDelegationRepository delegationRepository;

    @Transactional
    public DelegationResponse request(UUID ownerId, UUID challengeId, UUID targetUserId) {
        Challenge c = challengeRepository.findByIdAndDeletedAtIsNull(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        if (!c.isOwner(ownerId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);

        // 대상은 현재 ACTIVE MANAGER 여야 한다.
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(challengeId, targetUserId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (!target.isManager()) throw new BusinessException(ErrorCode.TARGET_NOT_MANAGER);

        // 챌린지당 유효(PENDING) 요청은 1건 — 있으면 취소 후 재요청.
        if (delegationRepository.existsByChallengeIdAndStatus(challengeId, DelegationStatus.PENDING))
            throw new BusinessException(ErrorCode.DELEGATION_ALREADY_PENDING);

        Instant now = Instant.now();
        ChallengeDelegation d = delegationRepository.saveAndFlush(
                ChallengeDelegation.request(challengeId, ownerId, targetUserId, now));
        return new DelegationResponse(d.getId().toString(), d.getStatus().name(), d.getExpiresAt().toString());
    }

    @Transactional
    public DelegationActionResponse respond(UUID actorId, UUID challengeId, UUID delegationId, String action) {
        // role swap 일관성을 위해 챌린지 행을 잠근다.
        Challenge c = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        ChallengeDelegation d = delegationRepository.findByIdAndChallengeId(delegationId, challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELEGATION_NOT_FOUND));

        if (!d.isPending()) throw new BusinessException(ErrorCode.DELEGATION_ALREADY_RESOLVED);
        Instant now = Instant.now();
        if (d.isExpired(now)) {
            d.expire(now);
            throw new BusinessException(ErrorCode.DELEGATION_EXPIRED);
        }

        return switch (action == null ? "" : action) {
            case "ACCEPT" -> accept(c, d, actorId, now);
            case "REJECT" -> {
                if (!actorId.equals(d.getTargetUserId()))
                    throw new BusinessException(ErrorCode.NOT_DELEGATION_TARGET);
                d.reject(now);
                yield new DelegationActionResponse(DelegationStatus.REJECTED.name(), null);
            }
            case "CANCEL" -> {
                if (!actorId.equals(d.getRequesterId()))
                    throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
                d.cancel(now);
                yield new DelegationActionResponse(DelegationStatus.CANCELED.name(), null);
            }
            default -> throw new BusinessException(ErrorCode.INVALID_DELEGATION_ACTION);
        };
    }

    /** 수락(대상자): 트랜잭션 role swap — 기존 OWNER→MEMBER, 대상→OWNER, creatorId 교체. */
    private DelegationActionResponse accept(Challenge c, ChallengeDelegation d, UUID actorId, Instant now) {
        if (!actorId.equals(d.getTargetUserId()))
            throw new BusinessException(ErrorCode.NOT_DELEGATION_TARGET);

        UUID oldOwnerId = c.getCreatorId();
        ChallengeMember oldOwner = memberRepository.findByChallengeIdAndUserId(c.getId(), oldOwnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        ChallengeMember target = memberRepository.findByChallengeIdAndUserId(c.getId(), d.getTargetUserId())
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        oldOwner.changeRole(MemberRole.MEMBER);
        target.changeRole(MemberRole.OWNER);
        c.transferOwnership(d.getTargetUserId());
        d.accept(now);
        return new DelegationActionResponse(DelegationStatus.ACCEPTED.name(), d.getTargetUserId().toString());
    }
}
