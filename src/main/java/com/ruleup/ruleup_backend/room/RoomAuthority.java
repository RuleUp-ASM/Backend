package com.ruleup.ruleup_backend.room;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.service.ChallengeQueryService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 방 내부 API 권한 단일 게이트(방 내부기능 §6). 방장 판정을 컨트롤러마다 흩뿌리지 않고 한 곳에 모은다.
 * 지금은 requireOwner 가 challenge.isOwner()(creatorId) 한 줄이지만, 운영 스프린트가 role 기반으로
 * 이관할 때 이 컴포넌트만 바꾸면 공지 전체가 따라온다.
 */
@Component
@RequiredArgsConstructor
public class RoomAuthority {

    private final ChallengeQueryService challengeQuery;

    /** ACTIVE 멤버 확인(조회용). 아니면 NOT_A_MEMBER. 챌린지 없으면 CHALLENGE_NOT_FOUND. */
    public Challenge requireMember(UUID challengeId, UUID userId) {
        Challenge c = loadChallenge(challengeId);
        boolean member = challengeQuery.findMembership(challengeId, userId)
                .map(ChallengeMember::isActive).orElse(false);
        if (!member) throw new BusinessException(ErrorCode.NOT_A_MEMBER);
        return c;
    }

    /** 방장(현행 creatorId) 확인(쓰기용). 아니면 NOT_CHALLENGE_OWNER. */
    public Challenge requireOwner(UUID challengeId, UUID userId) {
        Challenge c = loadChallenge(challengeId);
        if (!c.isOwner(userId)) throw new BusinessException(ErrorCode.NOT_CHALLENGE_OWNER);
        return c;
    }

    /** 내 멤버십(방 홈 myRole/todayStatus 용). ACTIVE 아니면 NOT_A_MEMBER. */
    public ChallengeMember requireMembership(UUID challengeId, UUID userId) {
        return challengeQuery.findMembership(challengeId, userId)
                .filter(ChallengeMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_A_MEMBER));
    }

    private Challenge loadChallenge(UUID challengeId) {
        return challengeQuery.findActiveChallenge(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }
}
