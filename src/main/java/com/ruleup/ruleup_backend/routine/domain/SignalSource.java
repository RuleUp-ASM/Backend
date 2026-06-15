package com.ruleup.ruleup_backend.routine.domain;

/**
 * 인증 신호의 구체 출처.
 *  - 자동: GEOFENCE, GPS, ACTIVITY, SLEEP, USAGE, APP_FEATURE, HC_RECORD, EXTERNAL_API
 *  - 수동: PHOTO, GROUP_CHECK
 * (DB ENUM 과 1:1)
 */
public enum SignalSource {
    GEOFENCE, GPS, ACTIVITY, SLEEP, USAGE, APP_FEATURE, HC_RECORD, EXTERNAL_API,
    PHOTO, GROUP_CHECK
}
