package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate 이 {@code java.sql.Timestamp} 로 적어 온 DATETIME 을 UTC 벽시계로 되돌린다.
 *
 * <p>운영(stg·prod) 접속은 {@code serverTimezone=Asia/Seoul} 이라 {@code Timestamp.from(instant)} 로 적은
 * 값은 KST 벽시계(UTC+9)로 들어갔다. 코드는 이제 {@code DbTime} 으로 UTC 벽시계를 적고 읽으므로, 이 컬럼들의
 * 기존 값을 같은 규약으로 옮긴다. 대상은 <b>JDBC {@code Timestamp} 경로로만 쓰인 컬럼</b>뿐이다 — JPA·
 * {@code UTC_TIMESTAMP()} 가 쓰는 컬럼(예: {@code resolved_at}, {@code dispatched_at})은 이미 UTC 라 건드리지 않는다.
 *
 * <p>얼마나 옮길지는 SQL 로 알 수 없다 — 세션 시간대가 아니라 <b>드라이버 접속 설정</b>이 정한 값이기 때문이다.
 * 그래서 이 접속으로 {@code Timestamp} 하나를 실제로 보내 드라이버가 어떤 벽시계로 적는지 재서 그만큼 뺀다.
 * 접속이 UTC 인 환경(로컬·테스트 기본값)에서는 0 이라 아무것도 바꾸지 않는다.
 */
public class V22__jdbc_written_datetimes_to_utc extends BaseJavaMigration {

    /** 테이블 → JDBC Timestamp 로만 쓰인 DATETIME 컬럼. */
    static final Map<String, List<String>> TARGETS = Map.ofEntries(
            Map.entry("challenge_cross_ranking_snapshot", List.of("snapshot_at")),
            Map.entry("challenge_kicks", List.of("kicked_at", "rejoin_available_at")),
            Map.entry("challenge_rejoin_backoffs", List.of("available_at")),
            Map.entry("signup_token_consumptions", List.of("expires_at")),
            Map.entry("verification_sync_sessions", List.of("issuedAt", "lastSeenAt")),
            Map.entry("verification_permission_waits", List.of("first_observed_at")),
            Map.entry("verification_location_signals", List.of("occurredAt", "receivedAt", "purgeAfter", "purgedAt")),
            Map.entry("verification_device_usage_signals", List.of("occurredAt", "receivedAt")),
            Map.entry("verification_health_connect_signals", List.of("occurredAt", "receivedAt")),
            Map.entry("anomaly_location_events", List.of("observedAt")),
            Map.entry("anomaly_device_usage_events", List.of("observedAt")),
            Map.entry("anomaly_health_connect_events", List.of("observedAt")));

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        long offsetSeconds = driverOffsetSeconds(connection);
        if (offsetSeconds == 0) return;
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, List<String>> target : TARGETS.entrySet()) {
                StringBuilder set = new StringBuilder();
                for (String column : target.getValue()) {
                    if (!set.isEmpty()) set.append(", ");
                    set.append('`').append(column).append("` = `").append(column)
                            .append("` - INTERVAL ").append(offsetSeconds).append(" SECOND");
                }
                statement.executeUpdate("UPDATE `" + target.getKey() + "` SET " + set);
            }
        }
    }

    /** 이 접속에서 {@code Timestamp.from(instant)} 가 UTC 벽시계보다 몇 초 앞서 적히는가. */
    static long driverOffsetSeconds(Connection connection) throws Exception {
        Instant probe = Instant.parse("2026-01-01T00:00:00Z");
        try (PreparedStatement ps = connection.prepareStatement("SELECT DATE_FORMAT(?, '%Y-%m-%d %H:%i:%s')")) {
            ps.setTimestamp(1, Timestamp.from(probe));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LocalDateTime written = LocalDateTime.parse(rs.getString(1),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return Duration.between(probe, written.toInstant(ZoneOffset.UTC)).getSeconds();
            }
        }
    }
}
