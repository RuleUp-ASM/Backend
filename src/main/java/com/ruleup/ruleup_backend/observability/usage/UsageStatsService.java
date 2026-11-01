package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.admin.stats.MemberScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서비스 이용 지표 — 방 구성(그룹·솔로 × 자동·수동)을 「지금」 기준으로 센다. 게이지 {@link UsageMetrics} 가 쓴다.
 *
 * <p>회원 범위는 회원 구성({@link com.ruleup.ruleup_backend.admin.stats.MemberProfileService})과 같다
 * ({@link MemberScope}) — 탈퇴자·운영자·개발자 시험 계정은 빼고 센다.
 *
 * <p>「지금 있는 방」은 시작 전·진행 중이고 지워지지 않았으며 <b>범위 안 회원이 한 명 이상 ACTIVE 로 참여 중인</b> 방이다.
 * 개발자 시험 계정만 남은 방이 섞이지 않게 사람 쪽에서 방을 고른다.
 *
 * <p>인증 방식은 {@code challenges.verification_type}(정렬·필터용으로 승격한 값)이 원본이고, 비어 있는 옛 행은
 * {@code verification_config.selectedMethod} 로 메운다.
 *
 * <p>관리자 서비스는 이 클래스를 쓰지 않는다 — 공개 API 의 게이지 갱신만 부른다(관리자 DB 계정 권한을 늘리지 않게).
 */
@Service
public class UsageStatsService {

    /** 방의 인증 방식 — AUTO / MANUAL. */
    static final String VERIFICATION = "COALESCE(c.verification_type,"
            + " JSON_UNQUOTE(JSON_EXTRACT(c.verification_config, '$.selectedMethod')))";
    /** 지금 있는 방의 ACTIVE 멤버십 — challenge_members cm · challenges c · users u 를 묶는다. */
    private static final String LIVE_MEMBERSHIPS = " FROM challenge_members cm JOIN challenges c ON c.id = cm.challenge_id"
            + " JOIN users u ON u.id = cm.user_id"
            + " WHERE cm.status = 'ACTIVE' AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL AND ";

    private final JdbcTemplate jdbc;
    private final String scope;

    public UsageStatsService(JdbcTemplate jdbc, @Value("${app.admin.stats-excluded-user-ids:}") String excludedUserIds) {
        this.jdbc = jdbc;
        this.scope = MemberScope.of(excludedUserIds).members();
    }

    /** 방 한 칸 — 모드 × 인증 방식 × 상태별 방 수와 그 방들의 참여 인원(멤버십 수). */
    public record RoomCell(String mode, String verification, String status, long rooms, long memberships) {}

    /** 참여 중인 사람 수 — 한 사람이 그룹·솔로 방에 다 있으면 양쪽에 센다(겹친다). total 은 중복 없이. */
    public record RoomPeople(long group, long solo, long auto, long manual, long total) {}

    public record RoomComposition(List<RoomCell> cells, RoomPeople people) {}

    @Transactional(readOnly = true)
    public RoomComposition rooms() {
        List<RoomCell> cells = new ArrayList<>();
        jdbc.query("SELECT c.mode AS mode, " + VERIFICATION + " AS vt, c.status AS st, COUNT(DISTINCT c.id) AS rooms,"
                        + " COUNT(*) AS members" + LIVE_MEMBERSHIPS + scope + " GROUP BY mode, vt, st",
                rs -> {
                    cells.add(new RoomCell(rs.getString("mode"), rs.getString("vt"), rs.getString("st"),
                            rs.getLong("rooms"), rs.getLong("members")));
                });
        Map<String, Object> p = jdbc.queryForMap("SELECT"
                + " COUNT(DISTINCT IF(c.mode = 'GROUP', cm.user_id, NULL)) AS g,"
                + " COUNT(DISTINCT IF(c.mode = 'SOLO', cm.user_id, NULL)) AS s,"
                + " COUNT(DISTINCT IF(" + VERIFICATION + " = 'AUTO', cm.user_id, NULL)) AS a,"
                + " COUNT(DISTINCT IF(" + VERIFICATION + " = 'MANUAL', cm.user_id, NULL)) AS m,"
                + " COUNT(DISTINCT cm.user_id) AS t" + LIVE_MEMBERSHIPS + scope);
        return new RoomComposition(cells, new RoomPeople(num(p.get("g")), num(p.get("s")), num(p.get("a")),
                num(p.get("m")), num(p.get("t"))));
    }

    static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
