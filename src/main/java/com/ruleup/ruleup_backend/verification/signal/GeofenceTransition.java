package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 지오펜스 트랜지션(§2.6). transition = ENTER|EXIT|DWELL. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeofenceTransition(String geofenceId, String transition, String at) {}
