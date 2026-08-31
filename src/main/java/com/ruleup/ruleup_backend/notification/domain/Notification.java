package com.ruleup.ruleup_backend.notification.domain;

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
 * 알림함 적재 본체 ({@code notifications}).
 *
 * <p><b>필수(A) 알림의 법적 고지가 이 행의 {@code createdAt} 에서 성립</b>한다. 그래서 생성 이후
 * 시각을 갱신하지 않으며, 재발송하더라도 여기는 건드리지 않는다 — 발송 시도는
 * {@link NotificationDelivery} 에만 쌓인다.
 *
 * <p>{@code type} 이 VARCHAR 인 이유는 페이지2 공지·댓글 5종을 DDL 없이 추가하기 위함이다.
 * {@code category} 는 레지스트리에서 복사해 저장한다 — 조회할 때마다 레지스트리를 뒤지지 않고,
 * 분류가 나중에 바뀌어도 <b>이미 발행된 알림의 법적 성격은 그대로 남는다</b>.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Column(name = "category", nullable = false, length = 1, updatable = false)
    private String category;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    /** <b>민감정보를 담지 않는다</b> — 제재 상세는 앱 안에서 본다. */
    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "deeplink", length = 200)
    private String deeplink;

    /** 중복 제어의 대상 식별자 — 챌린지 ID 등. */
    @Column(name = "target_key", length = 100)
    private String targetKey;

    /** <b>고지 성립 시각 — 불변.</b> updatable=false 로 경로 자체를 막아 둔다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    /** 유저 개별 삭제 — 소프트. 고지 기록 자체는 남는다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static Notification of(UUID userId, NotificationType type, String title, String body,
                                  String targetKey, String deeplink, Instant createdAt) {
        Notification n = new Notification();
        n.id = UuidGenerator.generate();
        n.userId = userId;
        n.type = type.name();
        n.category = type.category().code();
        n.title = title;
        n.body = body;
        n.targetKey = targetKey;
        n.deeplink = deeplink;
        n.createdAt = createdAt;
        return n;
    }

    public NotificationCategory categoryEnum() {
        return NotificationCategory.valueOf(category);
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(Instant at) {
        if (readAt == null) this.readAt = at;
    }

    /** 소프트 삭제 — 목록에서만 빠지고 고지 기록은 남는다. */
    public void delete(Instant at) {
        if (deletedAt == null) this.deletedAt = at;
    }
}
