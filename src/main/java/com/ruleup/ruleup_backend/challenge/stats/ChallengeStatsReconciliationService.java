package com.ruleup.ruleup_backend.challenge.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 원천 → Projection 정합성 재계산 배치 (탐색 백엔드 테크스펙 §13).
 *
 * <p>AFTER_COMMIT 이벤트는 서버가 커밋 직후 죽으면 유실될 수 있다. 이 배치는 그 안전망이다 —
 * <b>"통계는 하루 늦어도 된다"는 뜻이 아니다.</b> 정상 경로는 이벤트 직후 갱신이고, 여기서는
 * 유실·버그로 어긋난 값만 되돌린다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeStatsReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeStatsReconciliationService.class);

    private final JdbcTemplate jdbc;
    private final ChallengeStatsProjectionService projectionService;
    private final MeterRegistry meterRegistry;

    /** 매일 04:40 KST — 자동 삭제 배치(04:10) 뒤 저부하 시간대. */
    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        runOnce();
    }

    /**
     * 탐색 후보(진행 전·진행 중) 방의 통계를 전부 다시 계산한다.
     *
     * @return 실제로 값이 달라져 고쳐진 방의 수 — {@code stats_reconciliation_diff_count} 로 관측한다
     */
    public int runOnce() {
        List<byte[]> targets = jdbc.query(
                "SELECT id FROM challenges WHERE status IN ('UPCOMING', 'ACTIVE')",
                (rs, i) -> rs.getBytes(1));

        int diff = 0;
        for (byte[] id : targets) {
            UUID challengeId = toUuid(id);
            try {
                Snapshot before = snapshot(id);
                projectionService.refresh(challengeId);
                if (!Objects.equals(before, snapshot(id))) diff++;
            } catch (Exception e) {
                // 방 단위 격리 — 한 방이 실패해도 나머지는 계속 본다
                log.error("통계 재계산 실패 challengeId={}: {}", challengeId, e.getMessage(), e);
            }
        }
        if (diff > 0) {
            Counter.builder("stats_reconciliation_diff_count")
                    .description("원천과 달라 reconciliation에서 복구한 challenge_stats 수")
                    .register(meterRegistry)
                    .increment(diff);
            log.warn("stats_reconciliation_diff_count={} (대상 {}건) — 이벤트 유실 가능성 조사 필요",
                    diff, targets.size());
        }
        return diff;
    }

    /** 비율 컬럼은 DECIMAL 이라 문자열로 읽는다 — BigDecimal 스케일 차이로 diff 가 오탐되지 않게. */
    private Snapshot snapshot(byte[] challengeId) {
        return jdbc.query("SELECT qualified_member_count, qualified_success_member_count, completion_rate, " +
                                "total_progress_count, non_failed_member_count, retention_rate " +
                                "FROM challenge_stats WHERE challenge_id = ?",
                        (rs, i) -> new Snapshot(rs.getInt(1), rs.getInt(2), rs.getString(3),
                                rs.getInt(4), rs.getInt(5), rs.getString(6)),
                        challengeId)
                .stream().findFirst().orElse(null);
    }

    private record Snapshot(int qualified, int qualifiedSuccess, String completionRate,
                            int totalProgress, int nonFailed, String retentionRate) {}

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
