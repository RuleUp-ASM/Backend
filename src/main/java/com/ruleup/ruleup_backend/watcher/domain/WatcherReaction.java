package com.ruleup.ruleup_backend.watcher.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 응원·놀림 ({@code watcher_reactions}).
 *
 * <p><b>실패 건당 1회 제한을 서버 카운터가 아니라 복합 PK 로 보장</b>한다. 카운터로 풀면
 * 경합에서 초과가 나오지만 제약은 구조적으로 막는다 — 두 요청이 동시에 들어와도 하나는
 * 무결성 위반으로 떨어지고 그걸 409 로 바꿔 내린다.
 *
 * <p>같은 이유로 <b>응원과 놀림을 둘 다 보낼 수 없다.</b> 하나를 보내면 그 통지에 대한 반응은 끝난다.
 */
@Entity
@Table(name = "watcher_reactions")
@IdClass(WatcherReaction.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatcherReaction {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "notice_id", nullable = false, updatable = false)
    private UUID noticeId;

    /** 반응한 감시자 — <b>닉네임을 공개</b>한다. */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "watcher_user_id", nullable = false, updatable = false)
    private UUID watcherUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction", nullable = false, length = 10)
    private ReactionType reaction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static WatcherReaction of(UUID noticeId, UUID watcherUserId,
                                     ReactionType reaction, Instant at) {
        WatcherReaction r = new WatcherReaction();
        r.noticeId = noticeId;
        r.watcherUserId = watcherUserId;
        r.reaction = reaction;
        r.createdAt = at;
        return r;
    }

    /** 조회가 항상 두 값을 모두 가지고 들어오므로 추가 인덱스가 필요 없다. */
    public record Key(UUID noticeId, UUID watcherUserId) implements Serializable {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(noticeId, k.noticeId)
                    && Objects.equals(watcherUserId, k.watcherUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(noticeId, watcherUserId);
        }
    }
}
