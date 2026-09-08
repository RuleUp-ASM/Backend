package com.ruleup.ruleup_backend.notification.consumer;

import com.ruleup.ruleup_backend.notification.NotificationWindow;
import com.ruleup.ruleup_backend.notification.domain.NotificationToggleGroup;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.notification.queue.NotificationMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 발송 판정 — 공통 10절. <b>설정·중복·야간은 전부 발송 직전에 평가한다.</b>
 *
 * <p>순서가 계약이다: 읽음 → 야간 → 마스터 → 그룹 → 음소거 → 마케팅 창 → 인터벌 억제 → 활성 기기.
 * 야간이 마스터보다 앞인 것이 특히 중요하다 — 야간에 버리면 유저가 08:00 전에 토글을 다시 켜도
 * 그 알림은 되살아나지 않는다. <b>야간에 걸린 알림은 버리지 않고 미룬다.</b>
 *
 * <p>순수 함수다. 컨슈머 정지는 최적화일 뿐이고 <b>야간 0건을 실제로 보장하는 것은 이 재검증</b>이라,
 * 배포 타이밍이나 시계 오차로 21시 직후에 메시지를 받아도 여기서 막힌다.
 */
public record DispatchDecision(boolean shouldSend, boolean deferred, SuppressedReason suppressedReason) {

    private static final DispatchDecision SEND = new DispatchDecision(true, false, null);
    private static final DispatchDecision DEFER = new DispatchDecision(false, true, null);

    public static DispatchDecision send() {
        return SEND;
    }

    /** 야간 — 버리지 않고 08:00 까지 미룬다. 메시지를 삭제하지 않는다. */
    public static DispatchDecision defer() {
        return DEFER;
    }

    public static DispatchDecision suppressed(SuppressedReason reason) {
        return new DispatchDecision(false, false, reason);
    }

    public static DispatchDecision decide(NotificationMessage message, DispatchInputs in, Instant now) {
        // ① 읽음 — 야간 보류 중 유저가 알림 센터에 들어온 경우다. 설정을 이미 묶음 조회했으므로
        //    추가 쿼리가 없다.
        UUID readCursor = in.settings().readCursor(message.tab());
        if (isNotNewerThan(message.id(), readCursor))
            return suppressed(SuppressedReason.ALREADY_READ);

        // ② 야간 재검증 — 강퇴·계정 잠금도 예외가 아니다(절대 규칙 2).
        if (NotificationWindow.isNight(now)) return defer();

        // ③ 마스터 — 리마인더도 이 아래다.
        if (!in.settings().isPushEnabled()) return suppressed(SuppressedReason.MASTER_OFF);

        // ④ 그룹 — 그룹 토글이 없는 타입(리마인더·공지)은 통과한다.
        NotificationToggleGroup group = message.toggleGroup();
        if (group.isTogglable() && !in.settings().groupEnabled(group))
            return suppressed(SuppressedReason.GROUP_OFF);

        // ⑤ 챌린지 음소거 — 방이 없는 알림은 대상이 아니다.
        if (message.challengeId() != null && in.mutedChallengeIds().contains(message.challengeId()))
            return suppressed(SuppressedReason.MUTED);

        // ⑥ 마케팅 발송 창 — 야간 게이트가 먼저 잡으므로 정상 경로에서는 걸리지 않는다.
        //    시계 오차·경계 계산이 틀렸을 때를 막는 최후 방어이며, 이 값이 로그에 찍히면
        //    발송 경로를 즉시 차단해야 한다.
        if (group == NotificationToggleGroup.MARKETING && !decideMarketingWindow(now))
            return suppressed(SuppressedReason.MARKETING_WINDOW);

        // ⑦ 인터벌 억제 — 기준은 pushed_at(발송 성공 시각)이다.
        if (isWithinSuppressInterval(message, in, now))
            return suppressed(SuppressedReason.INTERVAL);

        // ⑧ 활성 기기 — 가장 마지막이다. 앞 게이트에 걸리면 그 사유가 기록돼야 한다.
        if (!in.hasActiveDevice()) return suppressed(SuppressedReason.NO_DEVICE);

        return send();
    }

    /** 08:00 이상 21:00 미만. 정보통신망법상 광고성 정보 전송이 허용되는 창이다. */
    public static boolean decideMarketingWindow(Instant at) {
        return NotificationWindow.isMarketingAllowed(at);
    }

    private static boolean isWithinSuppressInterval(NotificationMessage message,
                                                    DispatchInputs in, Instant now) {
        String key = message.suppressKey();
        if (key == null) return false;   // 22종 중 16종 — 억제를 쓰지 않는다

        Duration interval = NotificationType.find(message.type())
                .map(NotificationType::suppressInterval).orElse(null);
        if (interval == null) return false;

        Instant last = in.lastPushed().get(key);
        return last != null && last.plus(interval).isAfter(now);
    }

    /**
     * 읽음 판정 — UUIDv7 은 상위 48비트가 밀리초라 사전순 = 시간순이다.
     * {@code UUID.compareTo} 는 부호 있는 비교라 <b>부호 없는 비교</b>를 쓴다.
     */
    private static boolean isNotNewerThan(UUID candidate, UUID cursor) {
        if (cursor == null) return false;
        int high = Long.compareUnsigned(candidate.getMostSignificantBits(),
                cursor.getMostSignificantBits());
        if (high != 0) return high < 0;
        return Long.compareUnsigned(candidate.getLeastSignificantBits(),
                cursor.getLeastSignificantBits()) <= 0;
    }
}
