package com.ruleup.ruleup_backend.recommendation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V18 DB 에 이미 쌓인 아웃컴을 보존하면서 APPEAL 을 받게 되는지 별도 컨테이너에서 검증한다. */
@Testcontainers
class RoutineOutcomeVerifiedViaMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final String INSERT = """
            INSERT INTO RoutineOutcome (id, userId, challengeId, challengeMemberId, targetDate,
                status, verifiedVia, confirmedAt)
            VALUES (UNHEX(LPAD(?,32,'0')), UNHEX(LPAD('1',32,'0')), UNHEX(LPAD('2',32,'0')),
                UNHEX(LPAD('3',32,'0')), ?, 'SUCCESS', ?, NOW(6))
            """;

    @Test
    @DisplayName("V19 이후 이의 인용(APPEAL)으로 확정된 날도 수집되고, 기존 행은 그대로다")
    void appealBecomesStorable() {
        var dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("18").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update(INSERT, "a", "2026-09-01", "AUTO");
        jdbc.update(INSERT, "b", "2026-09-02", "MANUAL_FALLBACK");
        assertThatThrownBy(() -> jdbc.update(INSERT, "c", "2026-09-03", "APPEAL"))
                .as("V18 까지는 APPEAL 이 목록에 없어 03:30 수집이 통째로 실패했다")
                .hasMessageContaining("verifiedVia");

        Flyway current = Flyway.configure().dataSource(dataSource).target("19").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);

        jdbc.update(INSERT, "c", "2026-09-03", "APPEAL");
        assertThat(jdbc.queryForList("SELECT verifiedVia FROM RoutineOutcome ORDER BY targetDate", String.class))
                .containsExactly("AUTO", "MANUAL_FALLBACK", "APPEAL");
    }
}
