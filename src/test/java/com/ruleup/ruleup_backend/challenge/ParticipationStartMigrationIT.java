package com.ruleup.ruleup_backend.challenge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** V21 보정 — 재입장한 멤버만 이번 참여 시작 시각을 마지막 가입 사건으로 채운다. */
@Testcontainers
class ParticipationStartMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    @DisplayName("V21은 첫 가입보다 늦은 가입 사건이 있는 멤버만 participation_started_at 을 채운다")
    void backfillsOnlyRejoinedMembers() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("20").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("INSERT INTO users (id, oauth_provider, oauth_subject, nickname, approved_nickname) VALUES " +
                "(UNHEX(LPAD('1',32,'0')), 'KAKAO', 'm-owner', '방장', '방장'), " +
                "(UNHEX(LPAD('2',32,'0')), 'KAKAO', 'm-once', '한번', '한번'), " +
                "(UNHEX(LPAD('3',32,'0')), 'KAKAO', 'm-again', '재입장', '재입장')");
        jdbc.update("INSERT INTO challenges (id, owner_id, title, category, mode, repeat_days, start_date, " +
                "verification_config, params, penalty_config, reward_config) VALUES (UNHEX(LPAD('9',32,'0')), " +
                "UNHEX(LPAD('1',32,'0')), '방', 'EXERCISE', 'GROUP', '[]', CURRENT_DATE, '{}', '{}', '{}', '{}')");
        for (String u : new String[]{"2", "3"}) {
            jdbc.update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status, joined_at) VALUES " +
                    "(UNHEX(LPAD(?,32,'a')), UNHEX(LPAD('9',32,'0')), UNHEX(LPAD(?,32,'0')), 'MEMBER', 'ACTIVE', '2026-09-01 00:00:00')", u, u);
            jdbc.update("INSERT INTO challenge_join_events (id, challenge_id, user_id, joined_at) VALUES " +
                    "(UNHEX(LPAD(?,32,'b')), UNHEX(LPAD('9',32,'0')), UNHEX(LPAD(?,32,'0')), '2026-09-01 00:00:00')", u, u);
        }
        jdbc.update("INSERT INTO challenge_join_events (id, challenge_id, user_id, joined_at) VALUES " +
                "(UNHEX(LPAD('3',32,'c')), UNHEX(LPAD('9',32,'0')), UNHEX(LPAD('3',32,'0')), '2026-09-20 10:00:00')");

        Flyway current = Flyway.configure().dataSource(dataSource).target("21").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT participation_started_at FROM challenge_members WHERE user_id = UNHEX(LPAD('2',32,'0'))",
                java.time.LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT participation_started_at FROM challenge_members WHERE user_id = UNHEX(LPAD('3',32,'0'))",
                java.time.LocalDateTime.class)).isEqualTo(java.time.LocalDateTime.of(2026, 9, 20, 10, 0));
    }
}
