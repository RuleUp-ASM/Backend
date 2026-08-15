package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자 현재 점수·티어 (user_score_summaries 테이블). users와 1:1.
 * 점수 "왜 변했는지"는 score_transactions 원장이 담당 — 여기는 빠른 조회용 현재값.
 * 가입 트랜잭션에서 BRONZE 10점으로 생성된다 (기능 스펙 6-2 #5).
 */
@Entity
@Table(name = "user_score_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserScoreSummary extends AssignedIdEntity {

    /** 시작 티어 점수 — 브론즈 10점 (테크 스펙 §3). */
    public static final long INITIAL_SCORE = 10L;

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "total_score", nullable = false)
    private long totalScore;

    /** 점수만으로 계산한 실제 티어. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actual_tier", nullable = false)
    private Tier actualTier = Tier.UNRANKED;

    /** 강등 유예 등 정책 적용 후 사용자에게 표시하는 티어(방 입장 판정 기준). */
    @Enumerated(EnumType.STRING)
    @Column(name = "display_tier", nullable = false)
    private Tier displayTier = Tier.UNRANKED;

    @Column(name = "tier_grace_until")
    private Instant tierGraceUntil;

    /** 점수 동시 업데이트 낙관적 락. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public UUID getId() { return userId; }   // Persistable(신규 판별)용 — PK는 user_id

    /** 가입 시 초기 요약 — 브론즈 10점. */
    public static UserScoreSummary initialize(UUID userId) {
        UserScoreSummary s = new UserScoreSummary();
        s.userId = userId;
        s.totalScore = INITIAL_SCORE;
        s.actualTier = Tier.BRONZE;
        s.displayTier = Tier.BRONZE;
        return s;
    }

    /**
     * 응답용 "티어 내 점수 0~99" — 정식 밴드 계산은 온도 계산(티어) 스펙에서 확정한다.
     * 그 전까지는 총점을 0~99로 클램프해 내려준다(가입 직후 BRONZE 10 계약 충족).
     */
    public int scoreInTier() {
        return (int) Math.max(0, Math.min(totalScore, 99));
    }
}
