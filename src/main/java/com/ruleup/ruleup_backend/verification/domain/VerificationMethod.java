package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 방식(테크스펙 v2 §3). config가 어떤 평가기로 라우팅되는지 결정.
 *  - MVP 자동: GPS_PRESENCE / HEALTH / SCREEN_TIME / WAKE / SLEEP.
 *  - 수동: PHOTO / SELF_CHECK.
 *  - GPS_DISTANCE: 커스텀 세션 — Phase 2 하이브리드 전용. 코드/enum 보존하되 라우팅 안 함(§3).
 */
public enum VerificationMethod {
    GPS_PRESENCE, HEALTH, SCREEN_TIME, WAKE, SLEEP, PHOTO, SELF_CHECK,
    GPS_DISTANCE   // Phase 2 보존 — MVP 라우팅 제외
}
