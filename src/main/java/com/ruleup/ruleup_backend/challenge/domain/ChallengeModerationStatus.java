package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 챌린지 이미지 모더레이션(가시성) 게이트 상태 (생성 및 라이프사이클 스펙 §3-3).
 *  - NONE           : 이미지 없음. 검수 대상이 아니라 즉시 모집·노출 가능.
 *  - PENDING_REVIEW : 이미지 생성/변경 직후. 타인에게 비노출, 가입 차단(CHALLENGE_UNDER_REVIEW).
 *  - APPROVED       : 이미지 검수 통과 → 공개·가입 허용.
 *  - REJECTED       : 위반 → 알림 + 1시간 수정창(fixDeadline). 수정·재검수로 APPROVED 복귀 가능,
 *                     1시간 경과·미수정이면 자동 삭제(배치).
 *
 * lifecycle status / member setupStatus 와는 독립 축. 서로 섞지 않는다.
 * 이름(제목/설명) 검수는 하지 않는다(LLM draft Step2가 생성 시점에 대체) — 이 축은 이미지 전용.
 */
public enum ChallengeModerationStatus {
    NONE, PENDING_REVIEW, APPROVED, REJECTED;

    public boolean isApproved() { return this == APPROVED; }

    /** 모집·노출 허용 상태: 이미지 없음(NONE) 또는 이미지 검수 통과(APPROVED). */
    public boolean isPublicVisible() { return this == NONE || this == APPROVED; }
}
