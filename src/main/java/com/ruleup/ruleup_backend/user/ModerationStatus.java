package com.ruleup.ruleup_backend.user;

/**
 * 닉네임 / 프로필 사진의 LLM 검수 상태.
 *  - PENDING  : 가입(또는 변경) 직후 "확인 전". AI 검수를 아직 못 했거나(보류), 진행 대기.
 *  - APPROVED : "확인 후" 문제 없음 → 다른 사용자에게도 본인 값이 그대로 노출.
 *  - REJECTED : "확인 후" 문제 있음(정치/음란/욕설 등) → 타인에게는 임시 닉네임/사진 숨김.
 *
 * ⚠️ 중요: 이 상태는 가입을 막지 않는다. 가입은 항상 PENDING으로 일단 통과시키고,
 *          커밋 이후 비동기 검수로 DB 값만 바꾼다. (AI가 막히면 PENDING 유지 = 검수 보류)
 */
public enum ModerationStatus {
    PENDING, APPROVED, REJECTED
}
