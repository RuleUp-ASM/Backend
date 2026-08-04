package com.ruleup.ruleup_backend.moderation.domain;

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
import java.util.UUID;

/**
 * 닉네임·프로필 이미지 심사 요청/이력 (moderation_requests 테이블).
 * 사용자별 target 하나에 PENDING 요청은 하나만 존재(uq_moderation_user_pending_target —
 * DB generated column pending_target 기반). 완료된 요청은 이력으로 계속 누적된다.
 */
@Entity
@Table(name = "moderation_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationRequest extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, updatable = false)
    private ModerationTarget target;

    /** NICKNAME이면 신청 닉네임, PROFILE_IMAGE이면 이미지 URL/키. */
    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ModerationRequestStatus status = ModerationRequestStatus.PENDING;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Generated(event = EventType.INSERT)
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    public static ModerationRequest request(UUID userId, ModerationTarget target, String content) {
        ModerationRequest r = new ModerationRequest();
        r.id = UuidGenerator.generate();
        r.userId = userId;
        r.target = target;
        r.content = content;
        return r;
    }

    public void approve() {
        this.status = ModerationRequestStatus.APPROVED;
        this.decidedAt = Instant.now();
    }

    public void reject(String reason) {
        this.status = ModerationRequestStatus.REJECTED;
        this.rejectReason = (reason != null) ? reason : "커뮤니티 기준 위반";
        this.decidedAt = Instant.now();
    }
}
