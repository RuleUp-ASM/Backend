package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 챌린지별 연속 기록 — 사이클 판정 결과로만 움직인다.
 *
 * <p>{@code failureStreak} 는 점수 계산 밖으로도 나간다. 방 내부 기능 모듈이 2사이클 경고 ·
 * 3사이클 강퇴를 집행하는 입력이라, 사이클 실패 정의(달성률 50% 이하)를 두 도메인이 공유한다.
 * 정의는 여기가 원본이고 집행은 저쪽이다.
 *
 * <p>사건성 감점은 이 값을 바꾸지 않는다(정책 §4.8).
 */
@Entity
@Table(name = "challenge_streaks")
@IdClass(ChallengeStreak.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeStreak extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "success_streak", nullable = false)
    private int successStreak;

    @Column(name = "failure_streak", nullable = false)
    private int failureStreak;

    /** 마지막으로 반영한 사이클 회차 — 같은 사이클을 두 번 닫아도 연속 기록이 두 번 움직이지 않게 한다. */
    @Column(name = "last_cycle_no")
    private Integer lastCycleNo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static ChallengeStreak start(UUID userId, UUID challengeId) {
        ChallengeStreak s = new ChallengeStreak();
        s.userId = userId;
        s.challengeId = challengeId;
        return s;
    }

    /** 이 사이클을 이미 반영했는지 — 마감 멱등의 근거다. */
    public boolean alreadyApplied(int cycleNo) {
        return lastCycleNo != null && lastCycleNo >= cycleNo;
    }

    public void apply(CycleResult result, int cycleNo) {
        switch (result) {
            case SUCCESS -> { successStreak++; failureStreak = 0; }
            case PARTIAL -> failureStreak = 0;                     // 성공 연속은 유지한다
            case FAILURE -> { successStreak = 0; failureStreak++; }
        }
        this.lastCycleNo = cycleNo;
    }

    /** 정정 재계산 시 이 챌린지의 연속 기록을 되감는다. */
    public void rewindTo(int cycleNo) {
        this.lastCycleNo = (cycleNo <= 1) ? null : cycleNo - 1;
    }

    @Override
    public UUID getId() { return userId; }

    public record Key(UUID userId, UUID challengeId) implements Serializable {
        public Key() { this(null, null); }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(userId, k.userId) && Objects.equals(challengeId, k.challengeId);
        }

        @Override public int hashCode() { return Objects.hash(userId, challengeId); }
    }
}
