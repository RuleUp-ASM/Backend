package com.ruleup.ruleup_backend.room.domain;

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
 * 방 내부 기록 활동 로그(방 내부기능). 공지 등 방 내부 기록의 생성/수정/삭제를 append-only로 남긴다.
 * 챌린지·유저 FK가 없어 방(챌린지)이 하드 삭제된 뒤에도 감사 로그로 보존된다.
 */
@Entity
@Table(name = "RoomActivityLog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomActivityLog extends AssignedIdEntity {

    /** entityType 상수(방 내부 기록 종류). */
    public static final String ENTITY_NOTICE = "NOTICE";

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "actorId", updatable = false)
    private UUID actorId;

    @Column(name = "entityType", nullable = false, length = 40, updatable = false)
    private String entityType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "entityId", updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private RoomLogAction action;

    @Column(name = "payload", updatable = false)
    private String payload;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static RoomActivityLog of(UUID challengeId, UUID actorId, String entityType,
                                     UUID entityId, RoomLogAction action, String payload) {
        RoomActivityLog log = new RoomActivityLog();
        log.id = UuidGenerator.generate();
        log.challengeId = challengeId;
        log.actorId = actorId;
        log.entityType = entityType;
        log.entityId = entityId;
        log.action = action;
        log.payload = payload;
        return log;
    }
}
