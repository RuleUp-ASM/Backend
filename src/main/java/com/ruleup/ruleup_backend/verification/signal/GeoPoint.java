package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 측위 포인트. LOCATION(presence fallback)·RUNNING_SESSION(Phase 2 고빈도). at = 관측시각 ISO.
 *  - isMock: 위치 신호 필수(테크스펙 v2 §6.3). 누락 시 거부.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoPoint(double lat, double lng, Double accuracy, String at, Boolean isMock) {}
