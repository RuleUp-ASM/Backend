package com.ruleup.ruleup_backend.challenge.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 멤버 목록 응답(data) — 생성 및 라이프사이클 §7. 현재 멤버(ACTIVE)만 반환(승인제 폐기).
 * 익명 챌린지면 nickname 마스킹·profileImageUrl null.
 */
public record MemberListResponse(
        String challengeId,
        Integer participantCount,        // 현재 멤버 수 (통계와 동일)
        Integer maxParticipants,         // 최대 참여 인원(정원)
        List<Member> members
) {
    public record Member(
            String userId,
            String nickname,
            String profileImageUrl,
            String role,                 // OWNER / MANAGER / MEMBER
            BigDecimal mannerTemperature,
            String joinedAt              // ISO datetime
    ) {}
}