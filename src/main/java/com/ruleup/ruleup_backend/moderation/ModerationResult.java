package com.ruleup.ruleup_backend.moderation;

/**
 * LLM 콘텐츠 검수 결과.
 *  - APPROVED    : 문제 없음 → 타인에게도 노출.
 *  - REJECTED    : 정치/음란/욕설/비난 등 문제 → 임시 닉네임/사진 숨김 + 알림.
 *  - UNAVAILABLE : AI 호출 실패/미설정 → 검수 "보류"(PENDING 유지). 가입은 이미 끝났으니 영향 없음.
 */
public enum ModerationResult {
    APPROVED, REJECTED, UNAVAILABLE
}
