package com.ruleup.ruleup_backend.challenge.counter;

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
import java.util.UUID;

/**
 * 동시 참여 카운터 정합성 보정 (L2 안전망).
 *
 * <p>이벤트 시점 재계산(L1)이 정상이면 여기서 고칠 것이 <b>없어야 한다</b>. 그래서 이 배치의
 * {@code diff} 는 성능 지표가 아니라 <b>경보</b>다 — 0보다 크면 아직 해제가 누락되는 경로가 있다는 뜻이고,
 * {@code reason} 태그별 분포가 곧 "어디가 새는가"의 답이다.
 * (같은 철학: {@code ChallengeStatsReconciliationService})
 *
 * <p><b>2단계로 나눈 이유.</b> ① 스캔은 락을 전혀 잡지 않고 어긋난 사용자만 뽑는다(평시 0건).
 * ② 실제 수정은 사용자마다 행을 잠그고 <b>다시 확인</b>한다. 그래서 스캔 결과가 조금 낡아
 * 그 사이 사용자가 스스로 가입·탈퇴해 값이 맞아버렸어도 잘못 덮어쓰지 않는다.
 *
 * <p>주기가 짧은 이유: 이 값은 표시용 통계가 아니라 <b>사용자를 직접 차단하는 게이트</b>이고
 * 공개 상세의 {@code joinBlockReason} 으로도 노출된다. 하루를 기다리게 할 수 없다.
 */
@Service
@RequiredArgsConstructor
public class UserJoinCounterReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(UserJoinCounterReconciliationService.class);

    /** 한 번에 고칠 최대 사용자 수. 남은 건 다음 주기가 가져간다(평시 0건이라 도달할 일이 없다). */
    private static final int SCAN_LIMIT = 1000;

    private final JdbcTemplate jdbc;
    private final UserJoinCounterService joinCounterService;
    private final MeterRegistry meterRegistry;

    /**
     * 기본 5분. 테스트에서는 초기 지연을 크게 줘서 사실상 끄는데, 이 배치가 무차별이라
     * 카운터를 직접 심어 게이트를 검증하는 기존 IT 들(멤버십 없이 {@code setCounter(3)})을
     * 되돌려 놓기 때문이다. IT 는 {@link #runOnce()} 를 직접 부른다.
     */
    @Scheduled(fixedDelayString = "${app.join-counter.reconcile-interval-ms:300000}",
            initialDelayString = "${app.join-counter.reconcile-initial-delay-ms:60000}")
    public void runPeriodically() {
        runOnce();
    }

    /** 어긋난 카운터를 원천 기준으로 되돌린다. 실제로 값이 바뀐 사용자 수 반환. */
    public int runOnce() {
        List<UUID> drifted = scanDrifted();
        if (drifted.isEmpty()) return 0;

        int fixed = joinCounterService.recompute(drifted, "RECONCILE");
        if (fixed > 0) {
            Counter.builder("join_counter_reconciliation_diff_count")
                    .description("보정 배치가 되돌린 동시 참여 카운터 수 — 0이 정상, 0 초과는 해제 누락 신호")
                    .register(meterRegistry)
                    .increment(fixed);
            log.warn("join_counter_reconciliation diff={} 후보={} — 해제가 누락되는 경로가 남아 있는지 조사 필요",
                    fixed, drifted.size());
        }
        observeStuckChallenges();
        return fixed;
    }

    /** 저장값 ≠ 원천인 사용자. 락 없는 읽기 한 번. */
    private List<UUID> scanDrifted() {
        return jdbc.query(
                "SELECT c.user_id FROM user_challenge_counters c " +
                        "LEFT JOIN (SELECT m.user_id, COUNT(*) AS n " +
                        "             FROM challenge_members m " +
                        "             JOIN challenges ch ON ch.id = m.challenge_id " +
                        "            WHERE m.status = 'ACTIVE' AND ch.deleted_at IS NULL " +
                        "              AND ch.status <> 'COMPLETED' " +
                        "            GROUP BY m.user_id) t ON t.user_id = c.user_id " +
                        "WHERE c.active_join_count <> COALESCE(t.n, 0) LIMIT " + SCAN_LIMIT,
                (rs, i) -> toUuid(rs.getBytes(1)));
    }

    /**
     * 고치지 않고 <b>보기만</b> 하는 관측 — 종료일이 지났는데 종료되지 않은 방.
     * 종료 배치가 멈추면 그 방은 영원히 슬롯을 먹는데, 카운터 기준으로는 "정확한 값"이라
     * 위 보정으로는 절대 드러나지 않는다. 라이프사이클 배치 정지를 조기에 잡기 위한 창이다.
     */
    private void observeStuckChallenges() {
        // end_date 는 KST 달력 날짜다. CURDATE() 는 DB 세션(UTC) 기준이라 00~09시 KST 사이에는
        // 하루 전 날짜를 주고, 그 시간대에는 어제 끝난 방을 아직 안 끝난 것으로 본다.
        // UTC_TIMESTAMP() 는 세션 타임존과 무관하게 UTC 이므로 거기서 +09:00 으로 옮겨 KST 오늘을 만든다.
        Integer stuck = jdbc.queryForObject(
                "SELECT COUNT(*) FROM challenges " +
                        "WHERE status <> 'COMPLETED' AND deleted_at IS NULL " +
                        "  AND end_date < DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'))",
                Integer.class);
        if (stuck != null && stuck > 0) {
            log.warn("join_counter_stuck_challenges count={} — 종료일이 지났는데 COMPLETED 가 아닌 방이 있다"
                    + "(라이프사이클 배치 정지 의심). 이 방들은 참여자의 슬롯을 계속 점유한다", stuck);
        }
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
