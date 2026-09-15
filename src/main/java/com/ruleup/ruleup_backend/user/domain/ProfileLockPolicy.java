package com.ruleup.ruleup_backend.user.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** One calendar month; the image-first save may attach one nickname edit within ten minutes. */
public final class ProfileLockPolicy {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public static final Duration SAVE_SESSION=Duration.ofMinutes(10);
    private ProfileLockPolicy() {}
    public static Instant lockedUntil(Instant changedAt) {
        return changedAt==null ? null : changedAt.atZone(KST).plusMonths(1).toInstant();
    }
    public static boolean isLocked(Instant changedAt,Instant now) {
        return changedAt!=null && now.isBefore(lockedUntil(changedAt));
    }
    public static boolean isSameSaveSession(Instant changedAt,Instant now) {
        return changedAt!=null && !now.isBefore(changedAt) && now.isBefore(changedAt.plus(SAVE_SESSION));
    }
}
