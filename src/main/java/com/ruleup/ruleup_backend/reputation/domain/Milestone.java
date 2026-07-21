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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 마일스톤(append-only, 마이프로필 평판 히스토리 피드). 각 배치/이벤트가 자기 마일스톤을 멱등 적재.
 * (userId, type, dedupKey) 유니크로 중복 방지.
 */
@Entity
@Table(name = "Milestone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Milestone extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private MilestoneType type;

    @Column(name = "dedupKey", nullable = false, updatable = false, length = 60)
    private String dedupKey;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "achievedAt", nullable = false)
    private LocalDate achievedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static Milestone of(UUID userId, MilestoneType type, String dedupKey, String label, LocalDate achievedAt) {
        Milestone m = new Milestone();
        m.id = UuidGenerator.generate();
        m.userId = userId;
        m.type = type;
        m.dedupKey = dedupKey;
        m.label = label;
        m.achievedAt = achievedAt;
        return m;
    }
}
