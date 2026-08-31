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
 * 운영 조작 이력 ({@code admin_audit_logs}) — <b>append only</b>.
 *
 * <p>수정·삭제 경로를 두지 않는다. 재검토 대응과 개인정보 열람 감사의 <b>유일한 근거</b>이고,
 * 지울 수 있으면 근거가 아니기 때문이다. 샘플링도 하지 않는다.
 *
 * <p>{@code operatorId} 에는 <b>운영자가 아닌 계정 ID 가 들어올 수 있다</b> — 접근 거부도
 * 기록하기 때문이다. {@code result = DENIED} 의 급증이 우회 시도의 신호다.
 */
@Entity
@Table(name = "admin_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog extends AssignedIdEntity {

    public enum Result { ALLOWED, DENIED }

    public enum TargetType { USER, CHALLENGE, REPORT }

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "operator_id", updatable = false)
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private AdminAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, updatable = false)
    private TargetType targetType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 10, updatable = false)
    private Result result;

    /**
     * 요청 본문의 SHA-256. <b>본문을 통째로 남기지 않아</b> 민감정보가 로그로 새지 않게 한다 —
     * 제재 사유에는 신고 내용이 인용될 수 있다.
     */
    @Column(name = "payload_digest", length = 64, updatable = false)
    private String payloadDigest;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static AdminAuditLog of(UUID operatorId, AdminAction action, TargetType targetType,
                                   UUID targetId, Result result, String payloadDigest, Instant at) {
        AdminAuditLog l = new AdminAuditLog();
        l.id = UuidGenerator.generate();
        l.operatorId = operatorId;
        l.action = action;
        l.targetType = targetType;
        l.targetId = targetId;
        l.result = result;
        l.payloadDigest = payloadDigest;
        l.occurredAt = at;
        return l;
    }
}
