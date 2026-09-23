package com.ruleup.ruleup_backend.score.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.UUID;
import java.io.Serializable;
@Entity @Table(name="cycle_score_states") @IdClass(CycleScoreState.Key.class)
@Getter @NoArgsConstructor
public class CycleScoreState {
    @Id @JdbcTypeCode(SqlTypes.BINARY) @Column(name="user_id") private UUID userId;
    @Id @JdbcTypeCode(SqlTypes.BINARY) @Column(name="challenge_id") private UUID challengeId;
    @Id @JdbcTypeCode(SqlTypes.BINARY) @Column(name="cycle_id") private UUID cycleId;
    @Column(name="cycle_start_on") private LocalDate startedOn;
    @Column(name="cycle_end_on") private LocalDate endOn;
    @Column(name="membership_joined_at_snapshot") private Instant joinedAt;
    @Column(name="policy_version") private String policyVersion;
    @Enumerated(EnumType.STRING) @Column(name="tier_snapshot") private Tier tierSnapshot;
    @Column(name="target_count") private int targetCount;
    @Column(name="success_weight") private int successWeight;
    @Column(name="miss_weight") private int missWeight;
    @Column(name="success_count") private int successCount;
    @Column(name="miss_count") private int missCount;
    @Column(name="raw_cumulative") private int rawCumulative;
    @Column(name="limited_cumulative") private int limitedCumulative;
    @Enumerated(EnumType.STRING) @Column(name="cycle_result") private CycleResult cycleResult;
    @Column(name="success_streak_after") private Integer successStreakAfter;
    @Column(name="failure_streak_after") private Integer failureStreakAfter;
    @Column(name="closed_at") private Instant closedAt;
    @Column(name="version") private long version;
    public boolean isClosed() { return closedAt != null; }
    public record Key(UUID userId, UUID challengeId, UUID cycleId) implements Serializable {}
}
