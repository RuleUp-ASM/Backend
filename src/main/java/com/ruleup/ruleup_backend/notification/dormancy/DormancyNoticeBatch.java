package com.ruleup.ruleup_backend.notification.dormancy;

import com.ruleup.ruleup_backend.user.DormancyProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Backfilled activity rows drive notices and subsequent dormancy actions. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DormancyNoticeBatch {
    private final JdbcTemplate jdbc;
    private final DormancyProcessor processor;

    @SchedulerLock(name = "DormancyNoticeBatch.notifyInactive", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    public int notifyInactive() {
        byte[] after = new byte[16];
        int changed = 0;
        LocalDate line = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(23);
        while (true) {
            var ids = jdbc.query("SELECT a.user_id FROM user_activity a JOIN users u ON u.id=a.user_id " +
                    "WHERE a.user_id>? AND a.last_active_on<=? AND u.status<>'WITHDRAWN' AND u.role='MEMBER' " +
                    "ORDER BY a.user_id LIMIT 500", (rs, i) -> rs.getBytes(1), after, line);
            if (ids.isEmpty()) return changed;
            for (byte[] id : ids) {
                var buffer = ByteBuffer.wrap(id);
                UUID user = new UUID(buffer.getLong(), buffer.getLong());
                try { if (processor.advance(user)) changed++; }
                catch (RuntimeException failure) { log.warn("dormancy_processing_failed userId={}", user); }
            }
            after = ids.getLast();
        }
    }
}
