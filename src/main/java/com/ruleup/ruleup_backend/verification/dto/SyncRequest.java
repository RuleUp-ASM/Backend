package com.ruleup.ruleup_backend.verification.dto;

import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.util.List;

/** POST /sync 요청(§3.1). collectedAt = 멱등 키. */
public record SyncRequest(String collectedAt, List<SyncSignal> signals) {}
