package com.ruleup.ruleup_backend.me.dto;

import java.math.BigDecimal;

/** 마이 탭 메인 일괄 조회(GET /me/home). */
public record MeHomeResponse(
        String nickname,
        String nicknameStatus,       // PENDING / APPROVED / REJECTED
        String profileImageUrl,
        BigDecimal mannerTemperature,
        Counts counts
) {
    /** completed=완주 챌린지 수, inProgress=진행 중(미완주), groups=참여 중 그룹 챌린지 수. */
    public record Counts(int completed, int inProgress, int groups) {}
}
