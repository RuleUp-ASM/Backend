package com.ruleup.ruleup_backend.challenge.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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

    // 이 서비스에는 자체 일일 스케줄이 없다. 예전에는 04:40 에 전수 재계산을 따로 돌렸지만,
    // 03:30 탐색 대조가 <b>먼저 이 재계산을 수행한 뒤</b> 인덱스를 다시 만들도록 바뀌면서
    // 같은 전수 작업이 하루에 두 번이 됐다. 게다가 두 번째 회차가 끝나면 5분 보정이 그 결과를
    // 보고 다시 전수 재구성에 들어가, 매일 새벽 한 번 더 503 구간이 생겼다.
    // Redis 가 죽어 있어 03:30 이 걸러진 경우에도, 복구 직후 워밍업이 같은 재계산을 수행한다.

    /**
     * 탐색 후보(진행 전·진행 중) 방의 통계를 전부 다시 계산한다.
     *
     * @return 실제로 값이 달라져 고쳐진 방의 수 — {@code stats_reconciliation_diff_count} 로 관측한다
     */
    public int runOnce() {
        return run().diff();
    }

    /**
     * @param diff   값이 달라져 고쳐진 방 수
     * @param failed 재계산에 실패한 방 수 — <b>0 이 아니면 그 회차의 통계는 원천과 맞지 않는다.</b>
     *               호출부가 이 값을 보고 「준비 완료」를 게시할지 정한다. 삼키면 틀린 값이 준비된
     *               것으로 공표되고, 다음 회차까지 아무도 그 사실을 모른다.
     */
    public record Result(int diff, int failed) {}

    public Result run() {
        return run((java.time.Duration) null);
    }

    /**
     * @param lookback {@code null} 이면 후보 전체, 값이 있으면 <b>그만큼 거슬러 올라가 변경된 방만</b>.
     *                     5분 보정이 쓰는 경로다 — 매 회차 전수 재계산은 방이 늘수록 같은 일을
     *                     반복하고, 그 시간만큼 인기 하락 반영도 늦는다.
     */
    public Result run(java.time.Duration lookback) {
        long seconds = (lookback == null) ? 0L : Math.max(0L, lookback.toSeconds());
        List<byte[]> targets = (lookback == null)
                ? jdbc.query("SELECT id FROM challenges WHERE status IN ('UPCOMING', 'ACTIVE')",
                        (rs, i) -> rs.getBytes(1))
                : jdbc.query(
                        // 방 설정·멤버십·가입 사건 중 <b>하나라도</b> 그 뒤에 움직였으면 대상이다.
                        // 판정 확정은 멤버 행의 진행률을 건드리므로 challenge_members 가 그 신호다.
                        //
                        // 기준 시각은 <b>DB 가 자기 시계로</b> 만든다. 밖에서 Instant 를 넘기면
                        // 드라이버의 시간대 변환을 타고, 그게 어긋나면 조건이 늘 거짓이 되어
                        // 이 배치가 조용히 아무 방도 고르지 못한다.
                        "SELECT c.id FROM challenges c WHERE c.status IN ('UPCOMING', 'ACTIVE') AND ("
                                + "     c.updated_at >= (NOW(6) - INTERVAL ? SECOND) "
                                + "  OR EXISTS (SELECT 1 FROM challenge_members m "
                                + "              WHERE m.challenge_id = c.id "
                                + "                AND m.updated_at >= (NOW(6) - INTERVAL ? SECOND)) "
                                + "  OR EXISTS (SELECT 1 FROM challenge_join_events e "
                                + "              WHERE e.challenge_id = c.id "
                                + "                AND e.joined_at >= (NOW(6) - INTERVAL ? SECOND)))",
                        (rs, i) -> rs.getBytes(1), seconds, seconds, seconds);

        int diff = 0;
        int failed = 0;
        for (byte[] id : targets) {
            UUID challengeId = toUuid(id);
            try {
                Snapshot before = snapshot(id);
                projectionService.refresh(challengeId);
                if (!Objects.equals(before, snapshot(id))) diff++;
            } catch (Exception e) {
                // 방 단위 격리 — 한 방이 실패해도 나머지는 계속 본다. 다만 <b>세어서 알린다</b>.
                failed++;
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
        return new Result(diff, failed);
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
