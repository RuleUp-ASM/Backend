package com.ruleup.ruleup_backend.notification.announcement;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** null 이면 팬아웃 대기. */
    @Column(name = "fanned_out_at")
    private Instant fannedOutAt;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    public static Announcement of(String title, String body, UUID createdBy, Instant at) {
        Announcement a = new Announcement();
        a.id = UuidGenerator.generate();
        a.title = title;
        a.body = body;
        a.createdBy = createdBy;
        a.createdAt = at;
        return a;
    }

    public void markFannedOut(int recipients, Instant at) {
        this.recipientCount = recipients;
        this.fannedOutAt = at;
    }

    public boolean isPending() {
        return fannedOutAt == null;
    }
}
