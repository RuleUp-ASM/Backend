package com.ruleup.ruleup_backend.notification.domain;

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
 * 알림 설정 ({@code user_notification_settings}) — 마스터 1개 + 그룹 3종 + 읽음 커서 2개.
 * <b>유저당 1행</b>이다.
 *
 * <p><b>행이 없으면 전부 ON, 읽음 커서가 NULL 이면 전부 미읽음</b>으로 해석한다. 가입 시
 * 백필하지 않으므로 신규 유저도 조회가 성립한다.
 *
 * <p>세 계층은 <b>가장 제한적인 것이 이긴다</b> — 마스터가 OFF 면 그룹이 ON 이어도 나가지 않는다.
 * 어느 계층으로 막혀도 알림 센터 적재는 그대로다(절대 규칙 1).
 */
@Entity
@Table(name = "user_notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotificationSetting {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "group_account", nullable = false)
    private boolean groupAccount;

    @Column(name = "group_challenge", nullable = false)
    private boolean groupChallenge;

    @Column(name = "group_marketing", nullable = false)
    private boolean groupMarketing;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "last_read_notification_id")
    private UUID lastReadNotificationId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "last_read_announcement_id")
    private UUID lastReadAnnouncementId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 저장된 적 없는 유저의 해석값. 실제 행을 만들지 않고 그대로 응답에 쓸 수 있다. */
    public static UserNotificationSetting defaults(UUID userId, Instant at) {
        UserNotificationSetting s = new UserNotificationSetting();
        s.userId = userId;
        s.pushEnabled = true;
        s.groupAccount = true;
        s.groupChallenge = true;
        s.groupMarketing = true;
        s.updatedAt = at;
        return s;
    }

    /**
     * 이 그룹의 푸시가 허용되는가 — 마스터와 그룹을 AND 로 묶는다.
     * 그룹 토글이 없는 타입({@link NotificationToggleGroup#NONE})은 <b>그룹 판정을 통과</b>한다.
     */
    public boolean allowsPush(NotificationToggleGroup group) {
        if (!pushEnabled) return false;
        return switch (group) {
            case ACCOUNT -> groupAccount;
            case CHALLENGE -> groupChallenge;
            case MARKETING -> groupMarketing;
            case NONE -> true;
        };
    }

    public boolean groupEnabled(NotificationToggleGroup group) {
        return switch (group) {
            case ACCOUNT -> groupAccount;
            case CHALLENGE -> groupChallenge;
            case MARKETING -> groupMarketing;
            case NONE -> true;
        };
    }

    public void applyMaster(boolean enabled, Instant at) {
        this.pushEnabled = enabled;
        this.updatedAt = at;
    }

    public void applyGroup(NotificationToggleGroup group, boolean enabled, Instant at) {
        switch (group) {
            case ACCOUNT -> this.groupAccount = enabled;
            case CHALLENGE -> this.groupChallenge = enabled;
            case MARKETING -> this.groupMarketing = enabled;
            case NONE -> throw new IllegalArgumentException("그룹 토글이 없는 분류다: " + group);
        }
        this.updatedAt = at;
    }

    public UUID readCursor(NotificationTab tab) {
        return tab == NotificationTab.ANNOUNCEMENT ? lastReadAnnouncementId : lastReadNotificationId;
    }

    /**
     * 읽음 지점을 <b>앞으로만</b> 옮긴다. 현재 값보다 과거인 id 는 무시한다 —
     * 커서 페이징 2페이지에서 호출하면 읽음 지점이 과거로 밀려 이미 읽은 알림이 되살아난다.
     *
     * <p>서버가 {@code NOW()} 로 갱신하지 않는 것도 같은 이유다. 조회와 갱신 사이에 적재된
     * 알림이 화면에 뜬 적 없이 읽음 처리되면 <b>레드닷이 영영 뜨지 않는다</b>.
     */
    public void advanceReadCursor(NotificationTab tab, UUID notificationId, Instant at) {
        UUID current = readCursor(tab);
        if (!isNewer(notificationId, current)) return;
        if (tab == NotificationTab.ANNOUNCEMENT) this.lastReadAnnouncementId = notificationId;
        else this.lastReadNotificationId = notificationId;
        this.updatedAt = at;
    }

    /**
     * UUIDv7 은 상위 48비트가 밀리초 타임스탬프라 사전순 = 시간순이다.
     * {@code UUID.compareTo} 는 부호 있는 비교라 상위 비트가 켜지는 시점에 순서가 뒤집히므로
     * <b>부호 없는 비교</b>를 쓴다.
     */
    private static boolean isNewer(UUID candidate, UUID current) {
        if (candidate == null) return false;
        if (current == null) return true;
        int high = Long.compareUnsigned(candidate.getMostSignificantBits(),
                current.getMostSignificantBits());
        if (high != 0) return high > 0;
        return Long.compareUnsigned(candidate.getLeastSignificantBits(),
                current.getLeastSignificantBits()) > 0;
    }
}
