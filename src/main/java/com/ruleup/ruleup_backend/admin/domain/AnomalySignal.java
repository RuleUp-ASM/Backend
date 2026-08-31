package com.ruleup.ruleup_backend.admin.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 이상탐지 신호 ({@code anomaly_signals}).
 *
 * <p><b>탐지만으로는 제재하지 않는다.</b> 운영 검토 대상으로만 분류하며, 여기서 자동으로
 * 제재로 승격하는 경로는 두지 않는다 — 그 경로가 생기는 순간 "검토 없이 발동된 계정 제재
 * 0건" 가드레일이 깨진다.
 */
@Entity
@Table(name = "anomaly_signals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnomalySignal extends AssignedIdEntity {

    public enum SignalType { REPORT_ABUSE, APPEAL_ABUSE, MODERATION_EVASION }

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 30, updatable = false)
    private SignalType signalType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    /** 탐지 강도 — 임계값은 서버 설정이며 초기값을 넉넉히 잡고 조정한다. */
    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    /** null 이면 미검토. */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "reviewer_id")
    private UUID reviewerId;

    public static AnomalySignal of(SignalType type, UUID targetUserId, int score, Instant at) {
        AnomalySignal s = new AnomalySignal();
        s.id = UuidGenerator.generate();
        s.signalType = type;
        s.targetUserId = targetUserId;
        s.score = score;
        s.detectedAt = at;
        return s;
    }

    public void review(UUID reviewerId, Instant at) {
        this.reviewerId = reviewerId;
        this.reviewedAt = at;
    }
}
