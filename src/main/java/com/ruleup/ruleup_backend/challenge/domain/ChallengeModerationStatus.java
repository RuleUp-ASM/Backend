package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 챌린지 가시성(모더레이션) 게이트 상태 (CLAUDE.md §5.1).
 *  - PENDING_REVIEW : 생성/이름·이미지 변경 직후. 타인에게 비노출, 가입 차단(CHALLENGE_UNDER_REVIEW).
 *  - APPROVED       : LLM 검수 통과 → 공개·가입 허용.
 *  - REJECTED       : 위반 → 알림 + 1시간 수정창(fixDeadline). 수정·재검수로 APPROVED 복귀 가능,
 *                     1시간 경과·미수정이면 영구 닫힘(배치).
 *
 * lifecycle status / member setupStatus 와는 독립 축(§5.7). 서로 섞지 않는다.
 * 생성은 항상 성공하고, 이 상태만 비동기로 바뀐다(가입 핫패스에 외부 호출 금지).
 */
public enum ChallengeModerationStatus {
    PENDING_REVIEW, APPROVED, REJECTED;

    public boolean isApproved() { return this == APPROVED; }
}
