package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

/** Bounded hourly repair; every candidate is checked again in the normal dispatch transaction. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatcherNoticeRecovery {
    private final VerificationDailyRepository verifications;
    private final WatcherNoticeService notices;
    @Value("${app.watcher.recovery-max-age:PT24H}") private Duration maxAge;
    @Scheduled(cron = "0 35 * * * *", zone = "Asia/Seoul")
    public void recover() {
        Instant now = Instant.now();
        Instant since = now.minus(maxAge);
        UUID cursor = null;
        while (true) {
            var page = verifications.findWatcherRecoveryPage(since, now, cursor, PageRequest.of(0, 500));
            for (var d : page) {
                try { notices.onFailureConfirmed(d.getChallengeId(), d.getUserId(), d.getId(), d.getTargetDate(), d.getVerifiedAt()); }
                catch (RuntimeException e) { log.warn("Watcher recovery failed verificationId={}", d.getId(), e); }
            }
            if (page.size() < 500) return;
            cursor = page.getLast().getId();
        }
    }
}
