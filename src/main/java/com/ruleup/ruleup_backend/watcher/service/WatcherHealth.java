package com.ruleup.ruleup_backend.watcher.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Published as gauges; operators can stop dispatch with app.watcher.dispatch-enabled. */
@Component
public class WatcherHealth {
    private final JdbcTemplate jdbc;
    private final AtomicLong missingConsent = new AtomicLong();
    private final AtomicLong endedActive = new AtomicLong();
    private final AtomicLong earlyNotices = new AtomicLong();
    public WatcherHealth(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        metrics.gauge("watcher.active.missing.consent", missingConsent);
        metrics.gauge("watcher.ended.active", endedActive);
        metrics.gauge("watcher.notices.early", earlyNotices);
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void sample() {
        missingConsent.set(jdbc.queryForObject("SELECT COUNT(*) FROM watcher_relations WHERE status='ACTIVE' AND (accepted_at IS NULL OR consent_version IS NULL)", Long.class));
        endedActive.set(jdbc.queryForObject("SELECT COUNT(*) FROM watcher_relations r JOIN challenges c ON c.id=r.challenge_id WHERE r.status='ACTIVE' AND r.removed_at IS NULL AND (c.status='COMPLETED' OR c.deleted_at IS NOT NULL)", Long.class));
        earlyNotices.set(jdbc.queryForObject("SELECT COUNT(*) FROM watcher_notices n JOIN VerificationDaily v ON v.id=n.verification_id WHERE n.sent_at < CONVERT_TZ(DATE_ADD(v.targetDate,INTERVAL 2 DAY),'+09:00','+00:00') OR n.sent_at < v.appealClosesAt", Long.class));
    }
}
