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
 * 챌린지 공지 (Notice 테이블, 방 내부기능 §7.1). 방장이 작성, 게시형(채팅 아님).
 * 단일 pin(챌린지당 고정 1개), 소프트 삭제(deletedAt).
 */
@Entity
@Table(name = "Notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "authorId", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "deletedAt")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public static Notice create(UUID challengeId, UUID authorId, String title, String content, boolean pinned) {
        Notice n = new Notice();
        n.id = UuidGenerator.generate();
        n.challengeId = challengeId;
        n.authorId = authorId;
        n.title = title;
        n.content = content;
        n.pinned = pinned;
        return n;
    }

    public void edit(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public void pin()   { this.pinned = true; }
    public void unpin() { this.pinned = false; }
    public void softDelete() { this.deletedAt = Instant.now(); }
    public boolean isDeleted() { return deletedAt != null; }
}
