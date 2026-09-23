package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

/** 내 챌린지 목록의 탭 필터. 값을 잘못 주면 조용히 기본값으로 떨어뜨리지 않고 400 으로 돌려보낸다. */
public enum MyChallengeFilter {

    /** 진행 중 — 시작 전(UPCOMING) + 진행 중(ACTIVE) */
    IN_PROGRESS,

    /** 완료 — 완주·기간 만료. 방이 삭제된 뒤에도 이력에서 계속 보인다. */
    COMPLETED,

    /** 이탈 — 중도 탈퇴·강퇴·자동 탈퇴 */
    LEFT;

    public static MyChallengeFilter parse(String raw) {
        if (raw == null || raw.isBlank()) return IN_PROGRESS;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        }
    }
}
