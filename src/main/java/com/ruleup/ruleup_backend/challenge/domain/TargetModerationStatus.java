package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 항목별(제목/설명/이미지) 심사 상태 — 챌린지 생성·수정 스펙.
 *  - EXEMPT    : AI 생성·교정 원본 그대로(draft 대조 일치) → 심사 면제 (제목·설명 전용)
 *  - NONE      : 심사 대상 없음(이미지 없음 — 이미지 전용)
 *  - IN_REVIEW : 비동기 사후 심사 중 — 기능 제한 없음, 타인 화면은 대체 표시
 *  - APPROVED  : 심사 통과
 *  - REJECTED  : 심사 거부 — 수정 요청 푸시 + 대체 표시 유지
 */
public enum TargetModerationStatus {
    EXEMPT, NONE, IN_REVIEW, APPROVED, REJECTED;

    /** 타인 화면에 원본을 노출해도 되는 상태인가(심사 중·거부는 대체 표시). */
    public boolean isPubliclyVisible() {
        return this == EXEMPT || this == NONE || this == APPROVED;
    }
}
