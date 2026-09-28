package com.ruleup.ruleup_backend.agreement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V20 보정 — 가입 때 미동의로 채워진 상태 행만 「동의한 적 없음」으로 되돌린다. */
@Testcontainers
class AgreementNeverAgreedMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    @DisplayName("V20은 동의 이력이 없는 미동의 행의 version·agreed_at 만 비우고 철회·동의 행은 그대로 둔다")
    void backfillsOnlyNeverAgreedRows() {
        var dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("19").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                INSERT INTO users (id, oauth_provider, oauth_subject, nickname, approved_nickname)
                VALUES (UNHEX(LPAD('1',32,'0')), 'KAKAO', 'migration-agreement', '동의', '동의')
                """);
        // EVENT: 가입 때 거부만 했다 / MARKETING: 동의 후 철회 / TOS: 동의 중
        state(jdbc, "EVENT", 0);
        event(jdbc, "a1", "EVENT", 0);
        state(jdbc, "MARKETING", 0);
        event(jdbc, "a2", "MARKETING", 1);
        event(jdbc, "a3", "MARKETING", 0);
        state(jdbc, "TOS", 1);
        event(jdbc, "a4", "TOS", 1);

        Flyway current = Flyway.configure().dataSource(dataSource).target("20").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(row(jdbc, "EVENT")).containsEntry("version", null).containsEntry("agreed_at", null);
        assertThat(row(jdbc, "MARKETING").get("version")).isEqualTo("1.0");
        assertThat(row(jdbc, "MARKETING").get("agreed_at")).isNotNull();
        assertThat(row(jdbc, "TOS").get("version")).isEqualTo("1.0");
    }

    private static void state(JdbcTemplate jdbc, String type, int agreed) {
        jdbc.update("INSERT INTO user_agreement_states (user_id, agreement_type, agreed, version, agreed_at) " +
                "VALUES (UNHEX(LPAD('1',32,'0')), ?, ?, '1.0', NOW(3))", type, agreed);
    }

    private static void event(JdbcTemplate jdbc, String id, String type, int agreed) {
        jdbc.update("INSERT INTO user_agreement_events (id, user_id, agreement_type, agreed, version) " +
                "VALUES (UNHEX(LPAD(?,32,'0')), UNHEX(LPAD('1',32,'0')), ?, ?, '1.0')", id, type, agreed);
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, String type) {
        return jdbc.queryForMap("SELECT version, agreed_at FROM user_agreement_states WHERE agreement_type = ?", type);
    }
}
