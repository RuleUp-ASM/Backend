package com.ruleup.ruleup_backend.verification.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 판정 행의 불변식 점검 — 고치지 않고 <b>세기만</b> 한다.
 *
 * <p>stg 에서 코드 경로로 설명되지 않는 행이 실측됐다(QA APL 영역, 2026-09-19): 이의 기한이
 * 자정(KST)이 아닌 행, 확정 시각·공유 시각 없는 FAILED 행. 지금 코드는 둘 다 만들 수 없다 —
 * 기한은 귀속일로만 계산하고({@code VerificationDeadlines}), FAILED 는 두 시각을 함께 세우는
 * {@code confirmFailure} 로만 들어온다. 남은 원천은 과거 코드와 DB 직접 조작(QA 시간 조작)이다.
 *
 * <p>원본을 잃지 않도록 자동 보수하지 않는다. 복구는 tools/maintenance/repair-verification-daily.sql로
 * 지정한 ID·버전 한 건을 보관한 후 정상 확정 배치에 재투입한다. JPA 저장 가드가 재발을 막는다.
 * 재시도는 finalizeRetryAt에 저장하므로 정책 기한 검사와 충돌하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class VerificationIntegrityCheck {

    private static final Logger log = LoggerFactory.getLogger(VerificationIntegrityCheck.class);

    /** D+2 00:00 KST = 귀속일+1일 15:00 UTC. datetime 컬럼은 UTC 로 저장된다. */
    static final String SQL = "SELECT "
            + "COALESCE(SUM(status='FAILED' AND (verifiedAt IS NULL OR shareableAt IS NULL "
            + "  OR verifiedAt < TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00') "
            + "  OR shareableAt < verifiedAt)),0) AS failed_unconfirmed, "
            + "COALESCE(SUM(appealClosesAt IS NOT NULL "
            + "  AND appealClosesAt <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')),0) AS appeal_deadline_off, "
            + "COALESCE(SUM(finalizeAfter IS NULL "
            + "  OR finalizeAfter <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')),0) AS finalize_off "
            + "FROM VerificationDaily";

    private final JdbcTemplate jdbc;

    @SchedulerLock(name = "VerificationIntegrityCheck.report", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    public void report() {
        try {
            Map<String, Object> counts = check();
            long total = counts.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
            if (total > 0) {
                log.error("verification_integrity_violation failedUnconfirmed={} appealDeadlineOff={} finalizeOff={}",
                        counts.get("failed_unconfirmed"), counts.get("appeal_deadline_off"), counts.get("finalize_off"));
            } else {
                log.info("verification_integrity_ok");
            }
        } catch (RuntimeException e) {
            log.warn("verification_integrity_check_failed error={}", e.toString());
        }
    }

    public Map<String, Object> check() {
        return jdbc.queryForMap(SQL);
    }
}
