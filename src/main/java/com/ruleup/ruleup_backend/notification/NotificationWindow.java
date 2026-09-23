package com.ruleup.ruleup_backend.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 발송 시간대 판정 — 알림 및 알림함 기능 스펙 6-1 #5.
 *
 * <p>야간은 <b>21:00~08:00 KST 고정</b>이며 유저가 시간대를 고르지 않는다. 야간 수신 동의 약관이
 * 폐지되면서(2026-08-28) 동의 여부를 묻지 않고 분류만 보고 일괄 처리하게 됐다.
 *
 * <p>마케팅(C)은 반대 방향의 제약을 받는다 — <b>08~21시에만</b> 발송할 수 있다(정보통신망법).
 * 야간에 걸린 마케팅은 다음 아침으로 미루지 않고 <b>발송하지 않는다</b>. 광고를 미뤄 보낼
 * 이유가 없고, 큐에 쌓아 두면 경계 계산이 틀렸을 때 그대로 위반이 된다.
 */
public final class NotificationWindow {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 야간 시작 — 이 시각부터 다음 08:00 까지 기능(B) 푸시를 보류한다. */
    private static final LocalTime NIGHT_START = LocalTime.of(21, 0);
    /** 아침 요약 발송 시각. 02:00~03:00 점검 창과 00시 판정 배치를 피해 잡혀 있다. */
    public static final LocalTime MORNING = LocalTime.of(8, 0);

    private NotificationWindow() {}

    /** 21:00 이상이거나 08:00 미만이면 야간이다. */
    public static boolean isNight(Instant at) {
        LocalTime t = ZonedDateTime.ofInstant(at, KST).toLocalTime();
        return !t.isBefore(NIGHT_START) || t.isBefore(MORNING);
    }

    /** 마케팅 발송 가능 창 — 08:00 이상 21:00 미만. */
    public static boolean isMarketingAllowed(Instant at) {
        return !isNight(at);
    }

    /**
     * 다음 아침 08:00 KST. 이미 08시 전이면 <b>오늘</b> 08시다 —
     * 새벽 1시에 발생한 알림을 다음날 아침까지 31시간 묵히면 맥락이 사라진다.
     */
    public static Instant nextMorning(Instant at) {
        ZonedDateTime kst = ZonedDateTime.ofInstant(at, KST);
        ZonedDateTime todayMorning = kst.toLocalDate().atTime(MORNING).atZone(KST);
        return (kst.toLocalTime().isBefore(MORNING) ? todayMorning : todayMorning.plusDays(1)).toInstant();
    }
}
