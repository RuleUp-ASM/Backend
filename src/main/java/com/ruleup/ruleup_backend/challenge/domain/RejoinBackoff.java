package com.ruleup.ruleup_backend.challenge.domain;

import java.time.Duration;
import java.time.Instant;

/** Non-permanent kicks wait 1, 2, 4, ... weeks per challenge. */
public final class RejoinBackoff {
    private RejoinBackoff() {}
    // Only the MySQL DATETIME storage range limits the date; there is no policy cap.
    private static final Instant LAST_STORABLE = Instant.parse("9999-12-31T23:59:59Z");
    public static Instant availableAt(Instant kickedAt, int previousKickCount) {
        long remainingWeeks = Duration.between(kickedAt, LAST_STORABLE).toDays() / 7;
        long weeks = weeks(previousKickCount);
        return weeks > remainingWeeks ? LAST_STORABLE : kickedAt.plus(Duration.ofDays(7L * weeks));
    }
    public static long weeks(int previousKickCount) {
        return 1L << Math.min(Math.max(previousKickCount, 0), 62);
    }
}
