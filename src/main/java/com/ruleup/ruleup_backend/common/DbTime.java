package com.ruleup.ruleup_backend.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JdbcTemplate 로 DATETIME 을 읽고 쓸 때의 규약 — <b>UTC 벽시계</b>.
 *
 * <p>JPA({@code Instant} 매핑)·{@code UTC_TIMESTAMP()}·세션 시간대가 UTC 인 {@code NOW()} 는 전부 UTC
 * 벽시계를 적는다. 그런데 운영(stg·prod) 접속은 {@code serverTimezone=Asia/Seoul} 이라
 * {@code java.sql.Timestamp} 를 거치는 순간(쓰기 {@code Timestamp.from}, 읽기 {@code getTimestamp})
 * 드라이버가 KST 로 변환한다. 그러면 JDBC 로 쓴 값은 9시간 앞선 벽시계가 되고, JPA 가 쓴 값을 JDBC 로
 * 읽으면 9시간 이른 시각이 된다 — 알림함 시각(QA NOTI-13), 차단 시각(REP-07), 이의 마감(APL-05)이
 * 모두 이 모양이었다.
 *
 * <p>{@link LocalDateTime} 은 시간대가 없어 드라이버가 변환하지 않는다. 접속 설정과 무관하게 같은 값을
 * 읽고 쓰려면 이 두 메서드만 쓴다.
 */
public final class DbTime {

    private DbTime() {}

    /** 바인딩 값 — Instant 를 UTC 벽시계로. null 은 null. */
    public static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 읽기 — UTC 벽시계를 Instant 로. NULL 이면 null. */
    public static Instant read(ResultSet rs, int column) throws SQLException {
        LocalDateTime at = rs.getObject(column, LocalDateTime.class);
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    public static Instant read(ResultSet rs, String column) throws SQLException {
        LocalDateTime at = rs.getObject(column, LocalDateTime.class);
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }
}
