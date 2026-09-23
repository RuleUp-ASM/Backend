package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.explore.CategoryCountService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Daily candidates are rechecked inside each room's independent transaction. */
@Service
@RequiredArgsConstructor
public class ChallengeAutoDeleteService {
    private static final Logger log = LoggerFactory.getLogger(ChallengeAutoDeleteService.class);
    private final JdbcTemplate jdbc;
    private final ChallengeArchiveService archive;
    private final CategoryCountService categoryCountService;

    @SchedulerLock(name = "ChallengeAutoDeleteService.runDaily", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void runDaily() { runOnce(); }

    public void runOnce() {
        var targets = jdbc.query("SELECT c.id FROM challenges c WHERE " +
                        "(c.status='COMPLETED' AND c.end_date < DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 2 DAY)) " +
                        "OR NOT EXISTS (SELECT 1 FROM challenge_members m WHERE m.challenge_id=c.id AND m.status='ACTIVE') " +
                        "OR EXISTS (SELECT 1 FROM challenge_history h WHERE h.challenge_id=c.id AND h.close_reason='ADMIN')",
                (rs, i) -> { ByteBuffer b=ByteBuffer.wrap(rs.getBytes(1)); return new UUID(b.getLong(),b.getLong()); });
        int deleted = 0;
        for (UUID id : targets) {
            try { if (archive.deleteIfEligible(id)) deleted++; }
            catch (RuntimeException failure) {
                log.error("challenge_delete_failed challengeId={} error={}", id, failure.getClass().getSimpleName(), failure);
            }
        }
        if (deleted > 0) categoryCountService.evict();
        log.info("challenge_delete_batch candidates={} deleted={}", targets.size(), deleted);
    }
}
