package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 챌린지·사이클별 점수 누적 상태 — 정수 산식의 입력이자 순변동 ±20 한도의 원본.
 *
 * <p>이 행 하나에 카운트와 한도가 함께 있어서 <b>별도 주간 한도 테이블이 필요 없다.</b> 한도 단위가
 * 달력 주차도 계정 합산도 아니라 챌린지별 각 사이클이기 때문이다. 덕분에 잠금이 단순해지고
 * ("누가 한도를 썼나"가 행 자체로 답이 된다) 정정 재계산의 파급도 이 사이클 안으로 좁혀진다.
 *
 * <p>소수 컬럼이 없다. {@code settledSuccessPoints} 는 {@code f(W, N, successCount)} 그 자체라
 * 카운트만 맞으면 언제든 재계산되고, 저장하는 이유는 감사·대조용이다.
 */
@Entity
@Table(name = "cycle_score_states")
@IdClass(CycleScoreState.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CycleScoreState extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Id
    @Column(name = "cycle_no", nullable = false, updatable = false)
    private int cycleNo;

    /** 사이클 시작 시점의 <b>실제</b> 티어. 표시 티어는 배점에 쓰지 않는다 — 유예 중인 사용자에게 불리하다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tier_snapshot", nullable = false, updatable = false)
    private Tier tierSnapshot;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "miss_count", nullable = false)
    private int missCount;

    @Column(name = "settled_success_points", nullable = false)
    private int settledSuccessPoints;

    @Column(name = "settled_miss_points", nullable = false)
    private int settledMissPoints;

    /** 원점수 누계 — 전액 누적하며 클램핑하지 않는다. */
    @Column(name = "raw_cumulative", nullable = false)
    private int rawCumulative;

    /** 반영 누계 — 실제로 점수가 움직인 만큼만 전진한다. */
    @Column(name = "limited_cumulative", nullable = false)
    private int limitedCumulative;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_result")
    private CycleResult cycleResult;

    @Column(name = "started_on", nullable = false, updatable = false)
    private LocalDate startedOn;

    /**
     * 이 사이클에 접어 넣은 판정 중 가장 늦은 확정 시각. 정산 배치가 "어디까지 봤나"를 여기서 읽는다 —
     * 별도 워터마크 테이블을 두지 않고 정산 대상 자체에 남긴다.
     */
    @Column(name = "last_judged_at")
    private Instant lastJudgedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static CycleScoreState open(UUID userId, UUID challengeId, int cycleNo,
                                       Tier tierSnapshot, int targetCount, LocalDate startedOn) {
        CycleScoreState s = new CycleScoreState();
        s.userId = userId;
        s.challengeId = challengeId;
        s.cycleNo = cycleNo;
        s.tierSnapshot = tierSnapshot;
        s.targetCount = targetCount;
        s.startedOn = startedOn;
        return s;
    }

    /** 성공축 주간 배점 W. */
    public int successWeight() { return TierPoints.weeklyGain(tierSnapshot); }

    /** 미달축 주간 배점 W(절댓값). */
    public int missWeight() { return TierPoints.weeklyPenalty(tierSnapshot); }

    /**
     * 카운트를 원본대로 다시 맞추고, 이번에 반영해야 할 <b>원점수</b> 변화량을 돌려준다.
     * 반영 누계가 카운트만의 함수라 이 계산은 몇 번을 돌려도 같은 최종 상태에 수렴한다.
     */
    public int recount(int newSuccessCount, int newMissCount) {
        int newSuccessPoints = IntegerScore.f(successWeight(), targetCount, newSuccessCount);
        int newMissPoints = IntegerScore.f(missWeight(), targetCount, newMissCount);
        int rawDelta = (newSuccessPoints - settledSuccessPoints) - (newMissPoints - settledMissPoints);

        this.successCount = newSuccessCount;
        this.missCount = newMissCount;
        this.settledSuccessPoints = newSuccessPoints;
        this.settledMissPoints = newMissPoints;
        return rawDelta;
    }

    /** 워터마크를 전진시킨다. 뒤로 가지는 않는다 — 늦게 도착한 정정이 배치를 되감으면 안 된다. */
    public void advanceWatermark(Instant judgedAt) {
        if (judgedAt != null && (lastJudgedAt == null || judgedAt.isAfter(lastJudgedAt)))
            this.lastJudgedAt = judgedAt;
    }

    /** 한도 계산 결과를 사이클 상태에 반영한다. */
    public void applyLimit(CycleLimit.Result result) {
        this.rawCumulative = result.rawCumulative();
        this.limitedCumulative = result.limitedCumulative();
    }

    /** 사이클 마감 — 판정을 기록하고 닫는다. */
    public void close(CycleResult result, Instant at) {
        this.cycleResult = result;
        this.closedAt = at;
    }

    public boolean isClosed() { return closedAt != null; }

    /**
     * 정정 재계산의 출발점으로 되돌린다 — 카운트·반영 누계를 0으로 놓고 원본에서 다시 세게 한다.
     * 원장은 지우지 않는다(정정 관계만 남긴다).
     */
    public void resetForRecompute() {
        this.successCount = 0;
        this.missCount = 0;
        this.settledSuccessPoints = 0;
        this.settledMissPoints = 0;
    }

    @Override
    public UUID getId() { return userId; }   // Persistable(신규 판별)용 — 실제 PK 는 복합키다

    /** 복합 PK. */
    public record Key(UUID userId, UUID challengeId, int cycleNo) implements Serializable {
        public Key() { this(null, null, 0); }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return cycleNo == k.cycleNo && Objects.equals(userId, k.userId)
                    && Objects.equals(challengeId, k.challengeId);
        }

        @Override public int hashCode() { return Objects.hash(userId, challengeId, cycleNo); }
    }
}
