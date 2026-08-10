package com.ruleup.ruleup_backend.room.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomComment extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private CommentTargetType targetType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "parent_comment_id", updatable = false)
    private UUID parentCommentId;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RoomComment create(UUID challengeId, CommentTargetType targetType, UUID targetId,
                                     UUID authorId, UUID parentCommentId, String body) {
        RoomComment c = new RoomComment();
        c.id = UuidGenerator.generate();
        c.challengeId = challengeId;
        c.targetType = targetType;
        c.targetId = targetId;
        c.authorId = authorId;
        c.parentCommentId = parentCommentId;
        c.body = body;
        return c;
    }

    public void delete(Instant at) { this.deletedAt = at; }
}
