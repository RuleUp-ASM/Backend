package com.ruleup.ruleup_backend.routine.domain;

/**
 * 인증 신호의 구체 출처.
 *  - 자동: GEOFENCE, GPS, ACTIVITY, SLEEP, USAGE, APP_FEATURE, HC_RECORD, EXTERNAL_API
 *  - 수동: PHOTO(사진), GROUP_CHECK(그룹 체크), SELF_CHECK(직접 체크 = 체크형)
 *
 * <p>SELF_CHECK 는 자동 인증이 불가능한(=카탈로그에 매칭 안 된) 루틴의 기본 수동 방식이다.
 * verification 계층의 SELF_CHECK 방식과 대응(비-PHOTO 수동 → SELF_CHECK).
 * (DB ENUM 과 1:1 — 카탈로그 시드엔 PHOTO/GROUP_CHECK 만, SELF_CHECK 는 폴백 스냅샷 전용)
 */
public enum SignalSource {
    GEOFENCE, GPS, ACTIVITY, SLEEP, USAGE, APP_FEATURE, HC_RECORD, EXTERNAL_API,
    PHOTO, GROUP_CHECK, SELF_CHECK
}
