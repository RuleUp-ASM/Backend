package com.ruleup.ruleup_backend.challenge.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Only unconfirmed publications older than ten minutes are recovered. SQS owns consumer retries. */
@Service
@RequiredArgsConstructor
public class ChallengeModerationRetryService {
    private final JdbcTemplate jdbc;
    private final ChallengeModerationQueue queue;

    @Scheduled(fixedDelay = 300_000)
    public void retryStalledModeration() {
        jdbc.query("SELECT id FROM challenges WHERE moderation_enqueued_at IS NULL " +
                        "AND moderation_pending_since <= DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE) " +
                        "AND (moderation_title='IN_REVIEW' OR moderation_description='IN_REVIEW' OR moderation_image='IN_REVIEW') " +
                        "ORDER BY moderation_pending_since, id LIMIT 100",
                (rs, i) -> ChallengeModerationStore.uuid(rs.getBytes(1))).forEach(queue::publish);
    }
}
