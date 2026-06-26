package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 지오펜스 전환(테크스펙 v2 §6.2). geofenceId = challengeMemberId, transition = ENTER|EXIT|DWELL.
 *  - isMock: 위치 신호 필수(§6.3). null(누락)이면 sync 검증에서 거부 대상.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeofenceTransition(String geofenceId, String transition, String at, Boolean isMock) {}
