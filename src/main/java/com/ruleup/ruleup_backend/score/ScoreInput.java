package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.IncidentType;
import java.time.*;
import java.util.*;

/** Trusted domain input. Raw device signals and display text never enter the ledger. */
public record ScoreInput(Kind kind, String sourceId, int sourceVersion, Instant effectiveAt,
                         String authType, CycleSpec cycle, LocalDate targetDate, String judgement,
                         IncidentType incidentType, UUID challengeId, int confirmedDelta,
                         int completedWeeks, boolean exempt) {
    public ScoreInput {
        if(effectiveAt!=null)effectiveAt=effectiveAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
    public enum Kind { SIGNUP, DAILY, CLOSE, INCIDENT }
    public static final String POLICY = "2026-09-14";
    public record CycleSpec(UUID challengeId, UUID cycleId, int cycleNo, LocalDate startOn,
                            LocalDate endOn, Instant joinedAt, LocalDate firstScorableOn,
                            int target, List<LocalDate> eligibleDates, String policyVersion) {
        public Instant startsAt() { return startOn.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(); }
        public Instant closesAt() { return endOn.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().minusMillis(1); }
    }
}
