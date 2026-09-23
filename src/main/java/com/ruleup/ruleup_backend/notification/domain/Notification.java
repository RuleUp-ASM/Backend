package com.ruleup.ruleup_backend.notification.domain;

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
 * 알림 센터 본체 ({@code notifications}).
 *
 * <p><b>{@code createdAt} 이 고지 성립 시각이다</b> — 도메인 커밋과 같은 트랜잭션에서 INSERT 되고
 * 이후 갱신 경로가 없다. {@code updatable = false} 로 경로 자체를 막아 둔다.
 *
 * <p>{@code toggleGroup} 과 {@code tab} 은 레지스트리에서 <b>적재 시점에 복사</b>한다. 타입의
 * 그룹 귀속이 나중에 바뀌어도 이미 발행된 고지가 어느 토글로 걸러졌는지는 그대로여야 한다.
 *
 * <p>{@code readAt} 도 {@code deletedAt} 도 없다. 읽음은 {@link UserNotificationSetting} 의 커서
 * 두 개로 표현되고, 개별 삭제는 정책에서 폐지됐다.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /** UUIDv7 — 정렬 키이자 커서다. id 순서가 곧 시간 순서라 {@code created_at} 동점 문제가 없다. */
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** 0 알림 / 1 운영자 공지. 읽음 커서도 탭별로 따로다. */
    @Column(name = "tab", nullable = false, updatable = false)
    private byte tab;

    /** ENUM 이 아니다 — 타입 추가가 DDL 이 되면 안 된다. */
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private String type;

    @Column(name = "toggle_group", nullable = false, length = 10, updatable = false)
    private String toggleGroup;

    /**
     * <b>카운터 귀속 전용</b>이다. 렌더링·억제·음소거에 쓰이는 챌린지 값과 의미가 다르다 —
     * 감시자 통지는 챌린지에서 발생하지만 수신자가 방 멤버가 아니라 카운터가 뜰 자리가 없으므로
     * NULL 이다(공통 #19).
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", updatable = false)
    private UUID challengeId;

    /** 적재 시점에 렌더링해 고정한다. 템플릿이 바뀌어도 과거 고지 문구는 불변이다. */
    @Column(name = "title", nullable = false, length = 100, updatable = false)
    private String title;

    /** 알림함은 전문, 푸시는 200자에서 절단. <b>민감정보를 담지 않는다</b>. */
    @Column(name = "body", nullable = false, length = 500, updatable = false)
    private String body;

    @Column(name = "deeplink", length = 255, updatable = false)
    private String deeplink;

    /** 발행 멱등(1회성). UNIQUE 가 INSERT 단계에서 막는다. 키가 없으면 NULL 이다. */
    @Column(name = "dedup_key", length = 160, updatable = false)
    private String dedupKey;

    /** 인터벌 억제(반복성). 6개 타입만 쓰고 나머지는 NULL 이다. */
    @Column(name = "suppress_key", length = 160, updatable = false)
    private String suppressKey;

    /**
     * 푸시 성공 시각 — <b>억제 판정의 기준</b>이다. {@code createdAt} 으로 판정하면 억제된 행도
     * 시각을 남기므로, 이벤트가 인터벌보다 잦으면 직전 행이 항상 윈도우 안에 있어
     * <b>억제가 영원히 풀리지 않는다</b>.
     */
    @Column(name = "pushed_at")
    private Instant pushedAt;

    /** <b>고지 성립 시각 — 불변.</b> */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification of(UUID userId, NotificationType type, String title, String body,
                                  UUID challengeId, String deeplink,
                                  String dedupKey, String suppressKey, Instant createdAt) {
        Notification n = new Notification();
        n.id = UuidGenerator.generate();
        n.userId = userId;
        n.tab = type.tab().code();
        n.type = type.name();
        n.toggleGroup = type.toggleGroup().name();
        n.challengeId = challengeId;
        n.title = title;
        n.body = body;
        n.deeplink = deeplink;
        n.dedupKey = dedupKey;
        n.suppressKey = suppressKey;
        n.createdAt = createdAt;
        return n;
    }

    public NotificationTab tabEnum() {
        return NotificationTab.of(tab);
    }

    public NotificationToggleGroup toggleGroupEnum() {
        return NotificationToggleGroup.valueOf(toggleGroup);
    }

    /** 발송에 성공했고 억제 대상 타입일 때만 채운다 — 08:00 피크의 쓰기를 20% 수준으로 줄인다. */
    public void markPushed(Instant at) {
        this.pushedAt = at;
    }
}
