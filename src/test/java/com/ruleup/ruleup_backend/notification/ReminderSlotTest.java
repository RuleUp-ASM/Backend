package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.reminder.ReminderSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 슬롯 판정 — {@code dedup_key} 의 마지막 조각이라 <b>같은 실행이 같은 값을 내야</b> 한다.
 * 08:00 크론이 08:03 에 돌아도 MORNING 이어야 재시도가 두 줄로 쌓이지 않는다.
 */
class ReminderSlotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static ReminderSlot at(int hour, int minute) {
        return ReminderSlot.at(LocalDateTime.of(2026, 9, 8, hour, minute).atZone(KST).toInstant());
    }

    @Test
    @DisplayName("정각 세 슬롯")
    void onTheHour() {
        assertThat(at(8, 0)).isEqualTo(ReminderSlot.MORNING);
        assertThat(at(12, 0)).isEqualTo(ReminderSlot.NOON);
        assertThat(at(19, 0)).isEqualTo(ReminderSlot.EVENING);
    }

    @Test
    @DisplayName("지연 실행도 같은 슬롯이다 — 재시도가 두 줄로 쌓이면 안 된다")
    void lateRunKeepsTheSlot() {
        assertThat(at(8, 3)).isEqualTo(ReminderSlot.MORNING);
        assertThat(at(11, 59)).isEqualTo(ReminderSlot.MORNING);
        assertThat(at(18, 59)).isEqualTo(ReminderSlot.NOON);
        assertThat(at(20, 59)).isEqualTo(ReminderSlot.EVENING);
    }
}
