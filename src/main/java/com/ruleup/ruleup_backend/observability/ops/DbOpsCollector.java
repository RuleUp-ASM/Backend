package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.observability.metric.MetricContext;
import com.ruleup.ruleup_backend.observability.metric.MetricKey;
import com.ruleup.ruleup_backend.observability.metric.MinuteMetricBuffer;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MySQL 쪽 진단값을 1분마다 한 태스크가 읽어 {@code RuleUp/DB}(Env, Database) 로 보낸다 — 노션 「알람 카탈로그」 §5
 * DB-03·06·07·08. RDS 기본 지표(CPU·저장공간·지연)는 AWS/RDS 와 자원 감시기(Lambda)가 본다.
 *
 * <ul>
 *   <li>DbConnectionPct — {@code Threads_connected / max_connections}. RDS DatabaseConnections 와 다른 값이다</li>
 *   <li>OldestLockWaitSec · OldestTransactionSec — {@code information_schema.innodb_trx}(PROCESS 권한)</li>
 *   <li>DeadlockDelta — {@code INNODB_METRICS lock_deadlocks} 증가분</li>
 * </ul>
 *
 * <p>노션은 이 수집을 서울 전용 VPC Lambda(읽기 계정)로 제안한다. 지금은 앱 안에서 돈다 — 새 VPC 함수·DB 계정·
 * 비밀 경로 없이 P1 진단값을 먼저 쌓기 위해서다. 앱과 함께 멈추는 한계는 이 값들이 전부 P1 이라 받아들이고,
 * DB 가 실제로 죽으면 SLO·NATIVE 경보가 P0 로 잡는다.
 *
 * <p>쿼리 하나라도 실패하면(권한 부족 포함) 그 값은 보내지 않고 {@code RuleUp/Observe CollectorHeartbeat}
 * (Component=db-collector) 도 그 분엔 보내지 않는다 — OBS-01 이 「수집이 멈췄다」로 알린다. 0 으로 메우지 않는다.
 */
@Slf4j
@Component
@Profile({"prod", "stg"})
public class DbOpsCollector {

    public static final String DB = "RuleUp/DB";
    public static final String OBSERVE = "RuleUp/Observe";

    private final JdbcTemplate jdbc;
    private final MinuteMetricBuffer buffer;
    private final Map<String, String> dims;
    private final Map<String, String> heartbeatDims;
    private Long lastDeadlocks;

    public DbOpsCollector(JdbcTemplate jdbc, MinuteMetricBuffer buffer, MetricContext ctx) {
        this.jdbc = jdbc;
        this.buffer = buffer;
        this.dims = Map.of("Env", ctx.env(), "Database", "primary");
        this.heartbeatDims = Map.of("Env", ctx.env(), "Component", "db-collector");
    }

    @Scheduled(cron = "30 * * * * *")
    @SchedulerLock(name = "DbOpsCollector.collect", lockAtMostFor = "PT50S", lockAtLeastFor = "PT20S")
    public void collect() {
        boolean complete = true;
        complete &= gauge("DbConnectionPct", MetricKey.Unit.PERCENT, () -> {
            Long connected = jdbc.queryForObject(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_connected'", Long.class);
            Long max = jdbc.queryForObject("SELECT @@max_connections", Long.class);
            return max == null || max == 0 || connected == null ? null : 100.0 * connected / max;
        });
        complete &= gauge("OldestLockWaitSec", MetricKey.Unit.SECONDS, () -> jdbc.queryForObject(
                "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, trx_wait_started, NOW())), 0) "
                        + "FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'", Double.class));
        complete &= gauge("OldestTransactionSec", MetricKey.Unit.SECONDS, () -> jdbc.queryForObject(
                "SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, trx_started, NOW())), 0) FROM information_schema.innodb_trx",
                Double.class));
        try {
            Long total = jdbc.queryForObject(
                    "SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'", Long.class);
            if (total != null && lastDeadlocks != null) {
                buffer.add(MetricKey.count(DB, "DeadlockDelta", dims), Math.max(0, total - lastDeadlocks));
            }
            lastDeadlocks = total;
        } catch (RuntimeException e) {
            complete = false;
            log.warn("db_collector_failed metric=DeadlockDelta: {}", e.toString());
        }
        if (complete) buffer.gauge(MetricKey.count(OBSERVE, "CollectorHeartbeat", heartbeatDims), 1);
    }

    private boolean gauge(String name, MetricKey.Unit unit, java.util.function.Supplier<Double> query) {
        try {
            Double v = query.get();
            if (v == null) return false;
            buffer.gauge(new MetricKey(DB, name, dims, unit), v);
            return true;
        } catch (RuntimeException e) {
            log.warn("db_collector_failed metric={}: {}", name, e.toString());
            return false;
        }
    }
}
