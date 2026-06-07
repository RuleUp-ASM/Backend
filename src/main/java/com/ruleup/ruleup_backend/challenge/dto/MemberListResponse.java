package com.ruleup.ruleup_backend.challenge.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 3.8 멤버 목록 응답(data).
 * 익명 챌린지(CH-10)면 nickname 마스킹·profileImageUrl null.
 */
public record MemberListResponse(
        String challengeId,
        Integer participantCount,        // ACTIVE 멤버 수 (통계와 동일)
        List<Member> members
) {
    public record Member(
            String userId,
            String nickname,
            String profileImageUrl,
            String role,                 // OWNER / MEMBER
            String status,               // PENDING / ACTIVE
            BigDecimal mannerTemperature,// 승인 판단용 (PENDING 조회 시 유용)
            String joinedAt              // ISO datetime
    ) {}
}