package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 챌린지 lifecycle 상태 (챌린지 생성 및 라이프사이클 스펙 §2 — moderationStatus/setupStatus 와 독립 축).
 *  - UPCOMING  : 생성 직후 기본값(시작 전). 멤버 0명이면 전 항목 수정·삭제 가능.
 *  - ACTIVE    : 시작일 도달(진행 중). 인원 상한만 수정 가능.
 *  - COMPLETED : 종료. 가입·수정·삭제·탈퇴 전부 불가(기록 보존).
 * DB ENUM('UPCOMING','ACTIVE','COMPLETED')와 1:1.
 */
public enum ChallengeStatus {
    UPCOMING, ACTIVE, COMPLETED;

    /** 시작 전(UPCOMING) 상태인지. 전 항목 수정/삭제 허용의 전제. */
    public boolean isUpcoming() {
        return this == UPCOMING;
    }
}
