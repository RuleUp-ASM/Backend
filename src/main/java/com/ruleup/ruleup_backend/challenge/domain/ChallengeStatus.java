package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 챌린지 lifecycle 상태 (CLAUDE.md §5.7 — moderationStatus/setupStatus 와 독립 축).
 *  - RECRUITING : 생성 직후 기본값. 시작 전이라 수정/삭제 가능.
 *  - ACTIVE     : 시작일 도달. 이후 PATCH 불가(CHALLENGE_NOT_EDITABLE).
 *  - COMPLETED  : 종료.
 * DB ENUM('RECRUITING','ACTIVE','COMPLETED')와 1:1 (V8에서 ENDED→COMPLETED 정렬, DRAFT 제거).
 */
public enum ChallengeStatus {
    RECRUITING, ACTIVE, COMPLETED;

    /** 시작 전(수정·삭제 허용) 상태인지 */
    public boolean isEditable() {
        return this == RECRUITING;
    }
}
