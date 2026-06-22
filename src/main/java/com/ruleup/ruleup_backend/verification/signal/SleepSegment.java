package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 수면 세그먼트(§2.17). Android Sleep API가 익일 아침 일괄 전달. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepSegment(String startAt, String endAt, String status) {}
