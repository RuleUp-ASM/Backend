package com.ruleup.ruleup_backend.notification.announcement;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 운영자 공지 원본 — <b>팬아웃 전의 상태</b>다.
 *
 * <p>관리자 요청은 이 행 하나만 만들고 즉시 응답한다. 2만 행 INSERT 를 요청-응답 안에서 하면
 * 커넥션을 오래 잡고 실패 시 전부 롤백된다.
 */
@Entity
@Table(name = "announcements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 100, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, length = 500, updatable = false)
    private String body;

    /** MAINTENANCE / INCIDENT / TERMS / SHUTDOWN — 공통 5-2-1 A #10 확정값. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private Kind kind;

    @Column(name = "deep_link", length = 200, updatable = false)
    private String deepLink;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** null 이면 즉시. 미래면 그 시각이 지나야 잡이 집어 간다. */
    @Column(name = "scheduled_at", updatable = false)
    private Instant scheduledAt;

    /** 팬아웃 <b>전</b>에만 채워진다. 이미 적재된 공지는 회수되지 않는다. */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** null 이면 팬아웃 대기. */
    @Column(name = "fanned_out_at")
    private Instant fannedOutAt;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    /** 공지 종류 — 화면 분류이자 발행 이력의 필터다. */
    public enum Kind { MAINTENANCE, INCIDENT, TERMS, SHUTDOWN }

    public static Announcement of(Kind kind, String title, String body, String deepLink,
                                  UUID createdBy, Instant scheduledAt, Instant at) {
        Announcement a = new Announcement();
        a.id = UuidGenerator.generate();
        a.kind = kind;
        a.title = title;
        a.body = body;
        a.deepLink = deepLink;
        a.createdBy = createdBy;
        a.scheduledAt = scheduledAt;
        a.createdAt = at;
        return a;
    }

    public void markFannedOut(int recipients, Instant at) {
        this.recipientCount = recipients;
        this.fannedOutAt = at;
    }

    /**
     * 발행 취소 — <b>대기 중일 때만</b> 의미가 있다. 팬아웃이 끝났다면 이미 2만 개의 알림함에
     * 들어간 뒤라 되돌릴 대상이 없다.
     */
    public void cancel(Instant at) {
        this.canceledAt = at;
    }

    public boolean isPending() {
        return fannedOutAt == null && canceledAt == null;
    }
}
