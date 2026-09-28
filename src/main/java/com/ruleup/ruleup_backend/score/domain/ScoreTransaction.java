package com.ruleup.ruleup_backend.score.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="score_transactions") @Getter @NoArgsConstructor
@org.hibernate.annotations.Immutable
public class ScoreTransaction {
    @Id @JdbcTypeCode(SqlTypes.BINARY) @Column(name="id", updatable=false) private UUID id;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name="user_id", updatable=false) private UUID userId;
    @Column(name="entry_kind", updatable=false) private String entryKind;
    // reason·incident_type 은 varchar 라 코드가 모르는 값도 저장된다. enum 으로 매핑하면 그런 행이
    // 한 건만 섞여도 결과셋을 엔티티로 만드는 순간 조회 전체가 500 이 된다(QA TIER-06) — 원문으로 들고 읽을 때 푼다.
    @Column(name="reason", updatable=false) private String reasonCode;
    @Column(name="source_type", updatable=false) private String sourceType;
    @Column(name="source_event_key", updatable=false) private String sourceEventKey;
    @Column(name="source_version", updatable=false) private Integer sourceVersion;
    @Column(name="processing_key", updatable=false) private String processingKey;
    @Column(name="idempotency_key", updatable=false) private String idempotencyKey;
    @Column(name="effective_at", updatable=false) private Instant effectiveAt;
    @Column(name="effective_order", updatable=false) private byte[] effectiveOrder;
    @Column(name="policy_version", updatable=false) private String policyVersion;
    @Column(name="auth_type", updatable=false) private String authType;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name="challenge_id", updatable=false) private UUID challengeId;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name="cycle_id", updatable=false) private UUID cycleId;
    @Column(name="incident_type", updatable=false) private String incidentTypeCode;
    @Column(name="raw_delta", updatable=false) private int rawDelta;
    @Column(name="limited_delta", updatable=false) private int limitedDelta;
    @Column(name="applied_delta", updatable=false) private int appliedDelta;
    @Column(name="balance_after", updatable=false) private int balanceAfter;
    @Enumerated(EnumType.STRING) @Column(name="actual_tier_after", updatable=false) private Tier actualTierAfter;
    @Enumerated(EnumType.STRING) @Column(name="display_tier_after", updatable=false) private Tier displayTierAfter;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name="reversal_of", updatable=false) private UUID reversalOfId;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name="replacement_of", updatable=false) private UUID replacementOf;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="payload_json", updatable=false) private String payloadJson;
    @Column(name="created_at", updatable=false) private Instant createdAt;
    /** 원장 사유. 코드가 모르는 값이면 null. */
    public ScoreLedgerReason getReason() { return parse(ScoreLedgerReason.class, reasonCode); }
    /** 사건 유형. 비어 있거나 코드가 모르는 값이면 null. */
    public IncidentType getIncidentType() { return parse(IncidentType.class, incidentTypeCode); }
    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null) return null;
        try { return Enum.valueOf(type, raw); } catch (IllegalArgumentException e) { return null; }
    }
    public boolean isCycleLimitApplied() { return cycleId != null && rawDelta != limitedDelta; }
}
