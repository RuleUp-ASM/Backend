package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 앱 사용 이벤트(§2.13). type = RESUMED|PAUSED. 페어링해 구간 복원. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageEvent(String packageName, String type, String at) {}
