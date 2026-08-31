package com.ruleup.ruleup_backend.score.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 소급 정정 이력 — 정책 §4.10 "정정 전 기록은 삭제하지 않고 정정 관계를 남긴다".
 *
 * <p>{@code (원본, 정정 회차)} UNIQUE 가 같은 판정의 재정정을 막는다. 이의는 자동 인용이라
 * 같은 인증에 이의가 두 번 접수될 수 없지만, 재시도·중복 이벤트로 정정이 두 번 돌면 점수가
 * 두 번 오른다 — 그걸 DB 제약으로 막는다.
 */
@Entity
@Table(name = "score_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScoreCorrection extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** 정정된 원본 판정(= verificationDailyId). */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "original_event_id", nullable = false, updatable = false)
    private UUID originalEventId;

    @Column(name = "correction_version", nullable = false, updatable = false)
    private int correctionVersion;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Column(name = "cycle_no", nullable = false, updatable = false)
    private int cycleNo;

    /** 최초 영향 시점 — 이후 이벤트를 시간순으로 재계산하는 시작점. */
    @Column(name = "affected_from", nullable = false, updatable = false)
    private Instant affectedFrom;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public static ScoreCorrection of(UUID userId, UUID originalEventId, UUID challengeId,
                                     int cycleNo, Instant affectedFrom) {
        ScoreCorrection c = new ScoreCorrection();
        c.id = UuidGenerator.generate();
        c.userId = userId;
        c.originalEventId = originalEventId;
        c.correctionVersion = 1;
        c.challengeId = challengeId;
        c.cycleNo = cycleNo;
        c.affectedFrom = affectedFrom;
        return c;
    }

    @Override
    public UUID getId() { return id; }
}
