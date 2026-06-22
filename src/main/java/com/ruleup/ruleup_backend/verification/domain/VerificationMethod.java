package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 방식 (인증 스펙 §2.6). 챌린지 config가 어떤 평가기로 라우팅되는지 결정.
 * MVP 자동: GPS_PRESENCE / GPS_DISTANCE / SCREEN_TIME / WAKE / SLEEP. 수동: PHOTO / SELF_CHECK.
 */
public enum VerificationMethod {
    GPS_PRESENCE, GPS_DISTANCE, SCREEN_TIME, WAKE, SLEEP, PHOTO, SELF_CHECK
}
