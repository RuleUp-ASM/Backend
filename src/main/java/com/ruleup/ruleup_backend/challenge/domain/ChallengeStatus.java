package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 챌린지 상태 머신 (스펙 2.5).
 *  - DRAFT      : (예약) 본 스펙에선 사용하지 않음. 향후 임시저장용.
 *  - RECRUITING : 생성 직후 기본값. 시작 전이라 수정/삭제 가능.
 *  - ACTIVE     : 시작됨. 이후 수정 불가(CHALLENGE_NOT_EDITABLE).
 *  - ENDED      : 종료.
 * DB ENUM('DRAFT','RECRUITING','ACTIVE','ENDED')와 1:1.
 */
public enum ChallengeStatus {
    DRAFT, RECRUITING, ACTIVE, ENDED;

    /** 시작 전(수정·삭제 허용) 상태인지 */
    public boolean isEditable() {
        return this == RECRUITING || this == DRAFT;
    }
}