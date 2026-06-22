package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 측위 포인트. LOCATION(presence fallback)·RUNNING_SESSION(고빈도). at = 관측시각 ISO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoPoint(double lat, double lng, Double accuracy, String at) {}
