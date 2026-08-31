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
 * 동의·철회 이력 ({@code watcher_consent_logs}) — <b>append-only</b>.
 *
 * <p>수락·토글 OFF·차단 시각을 모두 쌓는다. 제3자 동의는 <b>입증 책임이 사업자에게</b> 있어서
 * "언제 동의했고 언제 닫았는지"를 뒤에서 재구성할 수 있어야 한다.
 *
 * <p>페이지2에서 채널이나 동의 범위가 세분화되면 <b>여기에 필드를 추가</b>하는 방식으로 확장한다.
 */
@Entity
@Table(name = "watcher_consent_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherConsentLog extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "relation_id", nullable = false, updatable = false)
    private UUID relationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 20, updatable = false)
    private ConsentEvent event;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static WatcherConsentLog of(UUID relationId, ConsentEvent event, Instant at) {
        WatcherConsentLog l = new WatcherConsentLog();
        l.id = UuidGenerator.generate();
        l.relationId = relationId;
        l.event = event;
        l.occurredAt = at;
        return l;
    }
}
