package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 화면 이벤트(§2.13, WAKE용). event = UNLOCK|SCREEN_ON. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenEvent(String event, String at) {}
