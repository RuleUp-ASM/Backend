package com.ruleup.ruleup_backend.challenge.moderation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Only unconfirmed publications older than ten minutes are recovered. SQS owns consumer retries. */
@Service
@RequiredArgsConstructor
public class ChallengeModerationRetryService {
    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationRetryService.class);
    private final JdbcTemplate jdbc;
    private final ChallengeModerationQueue queue;

    @SchedulerLock(name = "ChallengeModerationRetryService.retryStalledModeration", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    @Scheduled(fixedDelay = 300_000)
    public void retryStalledModeration() {
        // An unconfigured queue fails every row the same way. Retrying 100 of them each cycle
        // buries the one fact that matters — the queue is missing — under per-challenge noise.
        if (!queue.configured()) {
            Integer stalled = jdbc.queryForObject("SELECT COUNT(*) FROM challenges WHERE moderation_enqueued_at IS NULL " +
                    "AND (moderation_title='IN_REVIEW' OR moderation_description='IN_REVIEW' OR moderation_image='IN_REVIEW')",
                    Integer.class);
            log.error("moderation_retry_skipped reason=queue_unconfigured stalled={} "
                    + "(set MODERATION_QUEUE_URL, or MODERATION_LOCAL=true for in-process moderation)", stalled);
            return;
        }
        jdbc.query("SELECT id FROM challenges WHERE moderation_enqueued_at IS NULL " +
                        "AND moderation_pending_since <= DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) " +
                        "AND (moderation_title='IN_REVIEW' OR moderation_description='IN_REVIEW' OR moderation_image='IN_REVIEW') " +
                        "ORDER BY moderation_pending_since, id LIMIT 100",
                (rs, i) -> ChallengeModerationStore.uuid(rs.getBytes(1))).forEach(queue::publish);
    }
}
