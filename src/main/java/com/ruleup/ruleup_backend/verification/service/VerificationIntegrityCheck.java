package com.ruleup.ruleup_backend.verification.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>그래서 자동 보수는 하지 않는다. 원인을 모르는 채 고치면 조작으로 만든 QA 행과 실제 결함을
 * 구분할 기회가 사라진다. 대신 매일 한 번 건수를 남겨, 0 이 아니게 되는 날을 로그로 잡는다 —
 * 새 행이 계속 생기면 아직 모르는 쓰기 경로가 있다는 뜻이다.
 */
@Service
@RequiredArgsConstructor
public class VerificationIntegrityCheck {

    private static final Logger log = LoggerFactory.getLogger(VerificationIntegrityCheck.class);

    /** D+2 00:00 KST = 귀속일+1일 15:00 UTC. datetime 컬럼은 UTC 로 저장된다. */
    static final String SQL = "SELECT "
            + "COALESCE(SUM(status='FAILED' AND (verifiedAt IS NULL OR shareableAt IS NULL)),0) AS failed_unconfirmed, "
            + "COALESCE(SUM(appealClosesAt IS NOT NULL "
            + "  AND appealClosesAt <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')),0) AS appeal_deadline_off, "
            + "COALESCE(SUM(finalizeAfter IS NOT NULL "
            + "  AND finalizeAfter <> TIMESTAMP(DATE_ADD(targetDate, INTERVAL 1 DAY), '15:00:00')),0) AS finalize_off "
            + "FROM VerificationDaily";

    private final JdbcTemplate jdbc;

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
