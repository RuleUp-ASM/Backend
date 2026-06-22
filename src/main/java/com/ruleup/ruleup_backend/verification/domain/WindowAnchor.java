package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 종속 창 앵커(§2.11). 창 시작이 다른 method 결과에 종속될 때.
 * 예: "기상 후 1시간 폰 금지" → {base:WAKE, offsetMin:60, durationMin:60}.
 * 평가기는 같은 sync에서 base를 먼저 평가해 firstUnlockAt을 구한 뒤 창을 계산.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WindowAnchor(VerificationMethod base, int offsetMin, int durationMin) {}
