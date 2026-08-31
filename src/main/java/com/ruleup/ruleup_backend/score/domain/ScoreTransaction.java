package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 점수 변경 원장 1행. <b>append-only</b> — 정정도 새 행으로 쌓고 기존 행을 고치지 않는다.
 *
 * <p>변화량을 <b>셋</b>으로 나눠 남긴다. 하나라도 빠지면 감사도 재계산도 되지 않는다.
 * <ul>
 *   <li>{@code rawDelta} — 정책상 원래 계산된 값</li>
 *   <li>{@code limitedDelta} — 사이클 순변동 ±20 한도를 적용한 값</li>
 *   <li>{@code appliedDelta} — 0~2,000 범위까지 적용한 실제 반영량</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} 의 UNIQUE 제약이 최종 방어선이다. 정수 산식이 멱등이라 해도 원장에
 * 두 번 쌓이면 감사가 깨진다. 애플리케이션 메모리나 로컬 캐시로 멱등성을 보장하지 않는다 —
 * 다중 인스턴스와 재배포에 취약하다.
 *
 * <p>마이페이지는 이 원장에서 두 화면을 파생시킨다. 최근 변동 10건과, 월별 마지막 행의
 * {@code balanceAfter} 를 접어 만드는 티어 히스토리다.
 */
@Entity
@Table(name = "score_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScoreTransaction extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "raw_delta", nullable = false, updatable = false)
    private int rawDelta;

    @Column(name = "limited_delta", nullable = false, updatable = false)
    private int limitedDelta;

    @Column(name = "applied_delta", nullable = false, updatable = false)
    private int appliedDelta;

    @Column(name = "cycle_limit_applied", nullable = false, updatable = false)
    private boolean cycleLimitApplied;

    /** 반영 후 누적 점수 — 티어 히스토리의 월말 스냅샷이 여기서 나온다. */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private int balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private ScoreLedgerReason reason;

    /** 계정 단위 변동(운영자 조정 등)이면 null. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", updatable = false)
    private UUID challengeId;

    /** 사건성 감점은 사이클 한도를 거치지 않으므로 null. */
    @Column(name = "cycle_no", updatable = false)
    private Integer cycleNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", updatable = false)
    private IncidentType incidentType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "reversal_of_id", updatable = false)
    private UUID reversalOfId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** 루틴 점수 — 사이클 한도를 거친 변동. */
    public static ScoreTransaction routine(UUID userId, UUID challengeId, int cycleNo,
                                           ScoreLedgerReason reason, CycleLimit.Result result,
                                           String idempotencyKey) {
        ScoreTransaction t = base(userId, reason, result, idempotencyKey);
        t.challengeId = challengeId;
        t.cycleNo = cycleNo;
        return t;
    }

    /** 사건성 감점 — 한도를 거치지 않는다. */
    public static ScoreTransaction incident(UUID userId, UUID challengeId, IncidentType type,
                                            CycleLimit.Result result, String idempotencyKey) {
        ScoreTransaction t = base(userId, ScoreLedgerReason.INCIDENT, result, idempotencyKey);
        t.challengeId = challengeId;
        t.incidentType = type;
        return t;
    }

    /** 소급 정정으로 만들어진 되돌림. */
    public static ScoreTransaction reversal(UUID userId, UUID challengeId, int cycleNo,
                                            CycleLimit.Result result, String idempotencyKey) {
        ScoreTransaction t = base(userId, ScoreLedgerReason.REVERSAL, result, idempotencyKey);
        t.challengeId = challengeId;
        t.cycleNo = cycleNo;
        return t;
    }

    private static ScoreTransaction base(UUID userId, ScoreLedgerReason reason,
                                         CycleLimit.Result result, String idempotencyKey) {
        ScoreTransaction t = new ScoreTransaction();
        t.id = UuidGenerator.generate();
        t.userId = userId;
        t.reason = reason;
        t.rawDelta = result.rawDelta();
        t.limitedDelta = result.limitedDelta();
        t.appliedDelta = result.appliedDelta();
        t.cycleLimitApplied = result.cycleLimitApplied();
        t.balanceAfter = (int) result.scoreAfter();
        t.idempotencyKey = idempotencyKey;
        return t;
    }

    @Override
    public UUID getId() { return id; }
}
