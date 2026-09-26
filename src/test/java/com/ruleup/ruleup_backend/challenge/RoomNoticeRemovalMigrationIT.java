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

/** 기존 데이터와 FK가 있는 V17 DB의 업그레이드를 별도 컨테이너에서 검증한다. */
@Testcontainers
class RoomNoticeRemovalMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    @DisplayName("V18은 방 공지·읽음·부모/답글을 제거하고 운영자 공지와 전달된 알림을 보존한다")
    void upgradePreservesOperatorAnnouncements() {
        var dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("17").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                INSERT INTO users (id, oauth_provider, oauth_subject, nickname, approved_nickname)
                VALUES (UNHEX(LPAD('1',32,'0')), 'KAKAO', 'migration-owner', '방장', '방장')
                """);
        jdbc.update("""
                INSERT INTO challenges (id, owner_id, title, category, mode, repeat_days,
                    start_date, verification_config, params, penalty_config, reward_config)
                VALUES (UNHEX(LPAD('2',32,'0')), UNHEX(LPAD('1',32,'0')), '방', 'EXERCISE',
                    'GROUP', '[]', CURRENT_DATE, '{}', '{}', '{}', '{}')
                """);
        jdbc.update("""
                INSERT INTO Notice (id, challengeId, authorId, title, content, pinned)
                VALUES (UNHEX(LPAD('3',32,'0')), UNHEX(LPAD('2',32,'0')),
                    UNHEX(LPAD('1',32,'0')), '방 공지', '방 공지 본문', 1)
                """);
        jdbc.update("""
                INSERT INTO NoticeRead (id, noticeId, challengeId, userId, readAt)
                VALUES (UNHEX(LPAD('4',32,'0')), UNHEX(LPAD('3',32,'0')),
                    UNHEX(LPAD('2',32,'0')), UNHEX(LPAD('1',32,'0')), NOW())
                """);
        jdbc.update("""
                INSERT INTO room_comments (id, challenge_id, target_type, target_id, author_id, body)
                VALUES (UNHEX(LPAD('5',32,'0')), UNHEX(LPAD('2',32,'0')), 'NOTICE',
                    UNHEX(LPAD('3',32,'0')), UNHEX(LPAD('1',32,'0')), '댓글')
                """);
        jdbc.update("""
                INSERT INTO room_comments
                    (id, challenge_id, target_type, target_id, author_id, parent_comment_id, body)
                VALUES (UNHEX(LPAD('6',32,'0')), UNHEX(LPAD('2',32,'0')), 'NOTICE',
                    UNHEX(LPAD('3',32,'0')), UNHEX(LPAD('1',32,'0')), UNHEX(LPAD('5',32,'0')), '답글')
                """);
        jdbc.update("""
                INSERT INTO announcements (id, title, body, created_by, created_at)
                VALUES (UNHEX(LPAD('7',32,'0')), '운영자 공지', '점검 안내', UNHEX(LPAD('1',32,'0')), NOW())
                """);
        jdbc.update("""
                INSERT INTO notifications (id, user_id, tab, type, toggle_group, title, body, created_at)
                VALUES (UNHEX(LPAD('8',32,'0')), UNHEX(LPAD('1',32,'0')), 1, 'ANNOUNCEMENT', 'NONE',
                    '운영자 공지', '점검 안내', NOW())
                """);

        Flyway current = Flyway.configure().dataSource(dataSource).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                """, String.class))
                .doesNotContain("Notice", "NoticeRead", "room_comments")
                .contains("announcements", "notifications", "watcher_notices", "RoomActivityLog");
        assertThat(jdbc.queryForObject("SELECT body FROM announcements", String.class)).isEqualTo("점검 안내");
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE tab=1", String.class))
                .isEqualTo("점검 안내");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenges", Integer.class)).isEqualTo(1);
        assertThat(current.migrate().migrationsExecuted).isZero();
    }
}
