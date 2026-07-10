package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** POST /sync 응답(§3.1). flushIntervalSec = 다음 sync 주기(매 ACK마다 전체값, 기기 스펙 기반 산정). */
public record SyncResponse(
        String syncedAt,
        int flushIntervalSec,
        List<UpdatedChallenge> updatedChallenges,
        List<String> ignoredSignalTypes
) {
    public record UpdatedChallenge(String challengeId, String todayStatus, java.math.BigDecimal progressRate) {}
}
