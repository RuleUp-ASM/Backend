package com.ruleup.ruleup_backend.verification.domain;

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
 * 이의 제기 (Objection 테이블, 인증구현 §8.7).
 *  - 잠정 실패(FAILED_PROVISIONAL) 일자에 대해 멤버 본인이 제출(1일 창, 일자당 1회).
 *  - 방장(OWNER)/공동 관리자(MANAGER)가 승인/기각. 승인→SUCCESS(OBJECTION), 기각→FAILED(OBJECTION_REJECTED).
 *  - 제출 형식: 사진을 포함한 글 혹은 글만(content 필수, imageUrl 선택).
 */
@Entity
@Table(name = "Objection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Objection extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeMemberId", nullable = false, updatable = false)
    private UUID challengeMemberId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;                 // 제출자

    @Column(name = "targetDate", nullable = false, updatable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private ObjectionType type;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @Column(name = "imageUrl", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ObjectionStatus status;

    @Column(name = "deadline", nullable = false, updatable = false)
    private Instant deadline;            // 이의 제기 창 마감(잠정 실패 +1일 = daily.disputeClosesAt)

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "decidedBy")
    private UUID decidedBy;              // 처리한 방장/공동 관리자

    @Column(name = "decidedAt")
    private Instant decidedAt;

    @Column(name = "decisionReason", length = 500)
    private String decisionReason;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public static Objection submit(UUID challengeId, UUID challengeMemberId, UUID userId,
                                   LocalDate targetDate, ObjectionType type,
                                   String content, String imageUrl, Instant deadline) {
        Objection o = new Objection();
        o.id = UuidGenerator.generate();
        o.challengeId = challengeId;
        o.challengeMemberId = challengeMemberId;
        o.userId = userId;
        o.targetDate = targetDate;
        o.type = type;
        o.content = content;
        o.imageUrl = imageUrl;
        o.status = ObjectionStatus.PENDING;
        o.deadline = deadline;
        return o;
    }

    public boolean isPending() { return status == ObjectionStatus.PENDING; }

    public void approve(UUID adminId, Instant at, String reason) {
        this.status = ObjectionStatus.APPROVED;
        this.decidedBy = adminId;
        this.decidedAt = at;
        this.decisionReason = reason;
    }

    public void reject(UUID adminId, Instant at, String reason) {
        this.status = ObjectionStatus.REJECTED;
        this.decidedBy = adminId;
        this.decidedAt = at;
        this.decisionReason = reason;
    }
}
