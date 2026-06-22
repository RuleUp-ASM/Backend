package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** POST /sync 응답(§3.1). */
public record SyncResponse(
        String syncedAt,
        int nextSyncAfterSec,
        List<UpdatedChallenge> updatedChallenges,
        List<String> ignoredSignalTypes
) {
    public record UpdatedChallenge(String challengeId, String todayStatus, java.math.BigDecimal progressRate) {}
}
