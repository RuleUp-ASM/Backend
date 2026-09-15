package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** One user per transaction. Notices must exist and their lead time must pass before any action. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class DormancyProcessor {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final NotificationPublisher notifications;
    private final ChallengeMemberService members;
    private final UserAccountService accounts;
    private final com.ruleup.ruleup_backend.sanction.ReviewAccounts reviewAccounts;

    @Transactional
    public boolean advance(UUID id) {
        // 심사 계정은 휴면 처리에서 뺀다. 심사자는 업데이트가 올라올 때만 들어오므로 30일을 쉽게
        // 넘기는데, 그러면 챌린지가 전부 정리되고(D30) 1년이면 계정 자체가 사라진다 — 다음 심사
        // 때 열어 보면 아무것도 없다. 통지 단계까지 포함해 <b>아예 진행시키지 않는다</b>:
        // 심사자에게 「휴면 예정」 알림을 보내는 것도 앱을 잘못 설명하는 일이다.
        if (reviewAccounts.isExempt(id)) {
            log.info("review account exempt: userId={}, rule={}", id, "DORMANCY");
            return false;
        }

        var user = users.findByIdForUpdate(id).orElse(null);
        if (user == null || user.isWithdrawn()) return false;
        byte[] key = bytes(id);
        var rows = jdbc.queryForList("SELECT last_active_on,notified_stage FROM user_activity WHERE user_id=? FOR UPDATE", (Object) key);
        if (rows.isEmpty()) return false;
        LocalDate last = ((java.sql.Date) rows.getFirst().get("last_active_on")).toLocalDate();
        long days = ChronoUnit.DAYS.between(last, LocalDate.now(ZoneId.of("Asia/Seoul")));
        String stage = (String) rows.getFirst().get("notified_stage");
        String next;
        if (days >= 23 && "NONE".equals(stage)) {
            notify(id, last, "D7"); next = "D7";
        } else if (days >= 29 && "D7".equals(stage) && noticeMatured(id, last, "D7", 6)) {
            notify(id, last, "D1"); next = "D1";
        } else if (days >= 30 && "D1".equals(stage) && noticeMatured(id, last, "D1", 1)) {
            members.leaveAllExternally(id, "DORMANT"); next = "D30";
        } else if (days >= 335 && "D30".equals(stage)) {
            notify(id, last, "Y30"); next = "Y30";
        } else if (days >= 365 && "Y30".equals(stage) && noticeMatured(id, last, "Y30", 30)) {
            accounts.withdrawDormant(id); return true;
        } else return false;
        jdbc.update("UPDATE user_activity SET notified_stage=? WHERE user_id=?", next, key);
        return true;
    }

    private NotificationEvent event(UUID id, LocalDate last, String stage) {
        return NotificationEvent.of(id, "Y30".equals(stage) ? NotificationType.INACTIVE_WITHDRAWAL_NOTICE : NotificationType.DORMANCY_NOTICE,
                Map.of(NotificationParams.EVENT_KEY, "activity:" + last + ":" + stage));
    }

    private void notify(UUID id, LocalDate last, String stage) { notifications.publish(event(id, last, stage)); }

    private boolean noticeMatured(UUID id, LocalDate last, String stage, int leadDays) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE dedup_key=? AND created_at<=?",
                Integer.class, event(id, last, stage).dedupKey(), Timestamp.from(Instant.now().minus(leadDays, ChronoUnit.DAYS))) > 0;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
