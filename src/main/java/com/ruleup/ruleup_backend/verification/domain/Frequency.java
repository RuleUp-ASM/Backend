package com.ruleup.ruleup_backend.verification.domain;

/** 빈도형 목표(§2.12). 예: 주 3회 → {WEEK, 3}. */
public record Frequency(PeriodUnit unit, int count) {}
