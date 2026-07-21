package com.ruleup.ruleup_backend.reputation.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 일별 매너 온도 스냅샷 (마이프로필). 03:00 온도 배치가 하루 1행 멱등 적재.
 * 온도 상세 recentChanges + 통계 mannerDelta 의 유일한 정직한 이력(온도 계산은 현재값만 저장하므로).
 */
@Entity
@Table(name = "ReputationSnapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReputationSnapshot extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "snapshotDate", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    @Column(name = "temperature", nullable = false)
    private BigDecimal temperature;

    @Column(name = "delta", nullable = false)
    private BigDecimal delta;            // 전일 대비 변동(+/-)

    @Column(name = "label", length = 40)
    private String label;               // 규칙 라벨(자격일 유지 / 페이스 하락 등)

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static ReputationSnapshot of(UUID userId, LocalDate date, BigDecimal temperature,
                                        BigDecimal delta, String label) {
        ReputationSnapshot s = new ReputationSnapshot();
        s.id = UuidGenerator.generate();
        s.userId = userId;
        s.snapshotDate = date;
        s.temperature = temperature;
        s.delta = delta;
        s.label = label;
        return s;
    }
}
