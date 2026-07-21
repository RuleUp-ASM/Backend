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

/** 공지 읽음(§8.2). uq(noticeId, userId) — 상세 조회 시 멱등 upsert. 읽음 키는 userId(멤버십 유저당 1행). */
@Entity
@Table(name = "NoticeRead")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeRead extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "noticeId", nullable = false, updatable = false)
    private UUID noticeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;         // 미읽음 집계용 비정규화

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "readAt", nullable = false)
    private Instant readAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    public static NoticeRead of(UUID noticeId, UUID challengeId, UUID userId, Instant readAt) {
        NoticeRead r = new NoticeRead();
        r.id = UuidGenerator.generate();
        r.noticeId = noticeId;
        r.challengeId = challengeId;
        r.userId = userId;
        r.readAt = readAt;
        return r;
    }
}
