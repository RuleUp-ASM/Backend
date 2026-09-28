package com.ruleup.ruleup_backend.verification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V22 — JDBC Timestamp 로 적힌 KST 벽시계를 UTC 벽시계로 되돌린다. 보정량은 접속 설정에서 재므로
 * 운영과 같은 KST 접속에서만 움직이고 UTC 접속에서는 그대로다.
 */
@Testcontainers
class JdbcDatetimeUtcMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final Instant AT = Instant.parse("2026-09-27T15:30:00Z");

    private JdbcTemplate migrateTo21(String database, String timezone) {
        var root = new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        new JdbcTemplate(root).execute("CREATE DATABASE IF NOT EXISTS " + database);
        new JdbcTemplate(root).execute("GRANT ALL ON " + database + ".* TO '" + MYSQL.getUsername() + "'@'%'");
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database)
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?") + "serverTimezone=" + timezone;
        var ds = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).target("21").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 수정 전 코드처럼 Timestamp.from 으로 적는다.
        jdbc.update("INSERT INTO signup_token_consumptions (jti, expires_at) VALUES ('probe', ?)", Timestamp.from(AT));
        return jdbc;
    }

    private LocalDateTime stored(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT expires_at FROM signup_token_consumptions WHERE jti = 'probe'", LocalDateTime.class);
    }

    @Test
    @DisplayName("KST 접속에서 적힌 값은 9시간 되돌려 UTC 벽시계가 된다")
    void kstConnectionIsShifted() {
        JdbcTemplate jdbc = migrateTo21("tz_kst", "Asia/Seoul");
        assertThat(stored(jdbc)).isEqualTo(LocalDateTime.of(2026, 9, 28, 0, 30));   // KST 벽시계로 들어갔다

        Flyway.configure().dataSource(jdbc.getDataSource()).target("22").load().migrate();

        assertThat(stored(jdbc)).isEqualTo(LocalDateTime.of(2026, 9, 27, 15, 30));
    }

    @Test
    @DisplayName("UTC 접속에서는 애초에 어긋나지 않았으므로 바꾸지 않는다")
    void utcConnectionIsUntouched() {
        JdbcTemplate jdbc = migrateTo21("tz_utc", "UTC");
        Flyway.configure().dataSource(jdbc.getDataSource()).target("22").load().migrate();
        assertThat(stored(jdbc)).isEqualTo(LocalDateTime.of(2026, 9, 27, 15, 30));
    }
}
