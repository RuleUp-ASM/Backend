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
 * 푸시 발송 시도 기록 ({@code notification_deliveries}).
 *
 * <p><b>적재와 분리돼 있어 푸시가 실패해도 고지는 유효</b>하다. 야간 보류를 {@code scheduledAt}
 * 으로 표현하고 즉시 발송 건도 {@code scheduledAt = now} 로 두어 <b>하나의 경로로 통일</b>한다 —
 * 보류 큐와 즉시 발송을 따로 만들면 아침 요약 배치가 즉시 발송분을 못 보게 된다.
 */
@Entity
@Table(name = "notification_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery {

    /** 결과. FAILED 는 기록만 하고 재시도하지 않는다 — 알림함이 대체한다. */
    public enum Result { SUCCESS, FAILED, SUPPRESSED }

    /** 푸시를 생략한 이유. */
    public enum SuppressedReason { TOGGLE_OFF, MUTED, DEDUP, NIGHT_MARKETING, NO_DEVICE }

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    /** PUSH 만 있다 — SMS·이메일은 정책상 배제됐다. */
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** null 이면 미발송 — 보정 배치가 이 값으로 누락을 찾는다. */
    @Column(name = "sent_at")
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20)
    private Result result;

    @Enumerated(EnumType.STRING)
    @Column(name = "suppressed_reason", length = 30)
    private SuppressedReason suppressedReason;

    /** FCM 오류 코드 — <b>재시도하지 않고 기록만</b> 한다. */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    public static NotificationDelivery scheduled(UUID notificationId, Instant scheduledAt) {
        NotificationDelivery d = new NotificationDelivery();
        d.id = UuidGenerator.generate();
        d.notificationId = notificationId;
        d.channel = "PUSH";
        d.scheduledAt = scheduledAt;
        return d;
    }

    /** 푸시를 보내지 않기로 확정 — 적재는 이미 끝났으므로 고지는 유효하다. */
    public static NotificationDelivery suppressed(UUID notificationId, Instant at, SuppressedReason reason) {
        NotificationDelivery d = scheduled(notificationId, at);
        d.result = Result.SUPPRESSED;
        d.suppressedReason = reason;
        d.sentAt = at;   // 처리를 끝냈다는 뜻 — 보정 배치가 다시 집지 않게 한다
        return d;
    }

    public void markSent(Instant at) {
        this.sentAt = at;
        this.result = Result.SUCCESS;
    }

    public void markFailed(Instant at, String errorCode) {
        this.sentAt = at;
        this.result = Result.FAILED;
        this.errorCode = errorCode;
    }

    public boolean isPending() {
        return sentAt == null;
    }
}
