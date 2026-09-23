package com.ruleup.ruleup_backend.watcher.domain;

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
 * 실패 통지 발송 기록 ({@code watcher_notices}).
 *
 * <p>{@code verificationId} 가 <b>감사의 조인 키</b>다. 이 행의 {@code sentAt} 과 해당 인증 건의
 * 확정 시각을 대조해 <b>조기 발송 0건</b>을 감사한다 — 1건이라도 나오면 통지 플래그를 즉시 내린다.
 * 그래서 통지 내용이 아니라 <b>언제 보냈는지</b>가 이 테이블의 존재 이유다.
 */
@Entity
@Table(name = "watcher_notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherNotice extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "relation_id", nullable = false, updatable = false)
    private UUID relationId;

    /** 근거가 된 인증 건 — 확정 시각과 대조해 조기 발송을 감사한다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "verification_id", nullable = false, updatable = false)
    private UUID verificationId;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    public static WatcherNotice sent(UUID relationId, UUID verificationId, Instant at) {
        WatcherNotice n = new WatcherNotice();
        n.id = UuidGenerator.generate();
        n.relationId = relationId;
        n.verificationId = verificationId;
        n.sentAt = at;
        return n;
    }
}
