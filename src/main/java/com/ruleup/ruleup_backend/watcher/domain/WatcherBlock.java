package com.ruleup.ruleup_backend.watcher.domain;

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
 * 생성자–감시자 단위 30일 재초대/재등록 차단 원장(§5.9).
 * 수신거부/해제 시 기록되어, 같은 생성자가 같은 대상을 30일간 재초대/재동의하지 못하게 막는다.
 * subjectKey = USER: sha256("U:"+userId) / NON_USER: sha256("P:"+정규화번호) — 원본 PII는 저장하지 않는다.
 */
@Entity
@Table(name = "WatcherBlock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherBlock extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "inviterUserId", nullable = false, updatable = false)
    private UUID inviterUserId;

    @Column(name = "subjectKey", nullable = false, length = 64)
    private String subjectKey;

    @Column(name = "blockedUntil", nullable = false)
    private Instant blockedUntil;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static WatcherBlock of(UUID inviterUserId, String subjectKey, Instant blockedUntil) {
        WatcherBlock b = new WatcherBlock();
        b.id = UuidGenerator.generate();
        b.inviterUserId = inviterUserId;
        b.subjectKey = subjectKey;
        b.blockedUntil = blockedUntil;
        return b;
    }
}
