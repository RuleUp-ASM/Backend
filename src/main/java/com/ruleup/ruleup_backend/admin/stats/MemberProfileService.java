package com.ruleup.ruleup_backend.admin.stats;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.user.domain.Gender;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 회원 구성 — 나잇대·성별·가입 경로·기기·관심사·동의·활동을 「지금 회원」 기준으로 센다.
 *
 * <p>관리자 콘솔({@code GET /admin/dashboard/members})과 CloudWatch 게이지
 * ({@link com.ruleup.ruleup_backend.observability.MemberProfileMetrics})가 같은 값을 보도록 여기 한 곳에서 센다.
 *
 * <ul>
 *   <li>대상은 탈퇴하지 않은 일반 회원이다. 운영자 계정과 {@code app.admin.stats-excluded-user-ids}(개발자 시험 계정)는 뺀다.</li>
 *   <li>나이는 KST 오늘 기준 만 나이다. 생일은 가입 때 필수지만 옛 계정에 비어 있을 수 있어 {@code UNKNOWN} 칸을 둔다.</li>
 *   <li>고정 열거 칸(나잇대·성별·소셜·관심사·동의)은 0 이어도 늘 같은 순서로 전부 내려준다 — 콘솔과 게이지가 칸 목록을
 *       따로 들고 있지 않게. 앱이 보내는 값(앱 버전·OS·국가)은 있는 것만 많은 순으로 내린다.</li>
 * </ul>
 *
 * <p>집계 테이블을 두지 않는 이유는 {@link com.ruleup.ruleup_backend.admin.service.AdminDashboardService} 와 같다.
 * 회원 표 몇 개를 한 번 훑는 쿼리라 요청마다·게이지 주기마다 바로 센다.
 */
@Service
public class MemberProfileService {

    public static final List<String> DECADES = List.of("10", "20", "30", "40", "50+", "UNKNOWN");
    public static final List<String> FIVE_YEAR = List.of("14-19", "20-24", "25-29", "30-34", "35-39", "40-49", "50+", "UNKNOWN");
    public static final String UNKNOWN = "UNKNOWN";
    public static final List<String> ROOM_COUNTS = List.of("1", "2", "3", "4", "5+");

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** 회원 범위 — users 를 u 로 부를 때 WHERE 에 그대로 붙인다. */
    private final String scope;
    /** 운영자·제외 계정만 뺀 범위 — 탈퇴자를 셀 때. */
    private final String everyone;

    public MemberProfileService(JdbcTemplate jdbc, Clock clock,
                                @Value("${app.admin.stats-excluded-user-ids:}") String excludedUserIds) {
        this.jdbc = jdbc;
        this.clock = clock;
        MemberScope range = MemberScope.of(excludedUserIds);
        this.everyone = range.everyone();
        this.scope = range.members();
    }

    @Transactional(readOnly = true)
    public AdminDtos.MemberProfile snapshot() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        String fromUsers = " FROM users u LEFT JOIN user_information ui ON ui.user_id = u.id WHERE " + scope;

        Map<String, Object> age = jdbc.queryForMap("SELECT COUNT(*) AS n, SUM(ui.birth_date IS NULL) AS unknown,"
                + " ROUND(AVG(" + age(today) + "), 1) AS avg, MIN(" + age(today) + ") AS min, MAX(" + age(today) + ") AS max"
                + fromUsers);

        return new AdminDtos.MemberProfile(
                today.toString(),
                num(age.get("n")),
                new AdminDtos.AgeSummary(dbl(age.get("avg")), integer(age.get("min")), integer(age.get("max")),
                        num(age.get("unknown"))),
                fixed(DECADES, buckets("SELECT " + decade(today) + " AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k")),
                fixed(FIVE_YEAR, buckets("SELECT " + fiveYear(today) + " AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k")),
                fixed(names(Gender.values(), UNKNOWN), buckets("SELECT IFNULL(ui.gender, 'UNKNOWN') AS k, COUNT(*) AS n"
                        + fromUsers + " GROUP BY k")),
                ageByGender(fromUsers, today),
                fixed(names(OAuthProvider.values()), buckets("SELECT u.oauth_provider AS k, COUNT(*) AS n"
                        + fromUsers + " GROUP BY k")),
                list(buckets("SELECT IFNULL(u.platform, 'UNKNOWN') AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k ORDER BY n DESC")),
                list(buckets("SELECT IFNULL(u.app_version_name, 'UNKNOWN') AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k ORDER BY n DESC")),
                list(buckets("SELECT IFNULL(u.os_version, 'UNKNOWN') AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k ORDER BY n DESC")),
                list(buckets("SELECT IFNULL(u.country_code, 'UNKNOWN') AS k, COUNT(*) AS n" + fromUsers + " GROUP BY k ORDER BY n DESC")),
                fixed(names(InterestCategory.values()), buckets("SELECT i.category AS k, COUNT(*) AS n"
                        + " FROM user_interests i JOIN users u ON u.id = i.user_id WHERE " + scope + " GROUP BY k")),
                list(buckets("SELECT (SELECT COUNT(*) FROM user_interests i WHERE i.user_id = u.id) AS k,"
                        + " COUNT(*) AS n FROM users u WHERE " + scope + " GROUP BY k ORDER BY k")),
                consents(),
                activity(now),
                engagement(),
                participation(),
                withdrawals());
    }

    /**
     * 참여 중인 방 개수 순위 — 같은 개수는 같은 등수(1, 1, 3). 같은 등수 안에서는 직접 만든 방이 많은 순.
     * 콘솔 순위표와 대시보드 ④ 순위 칸({@link com.ruleup.ruleup_backend.observability.MemberProfileMetrics})이 쓴다.
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.RoomRank> roomRanking(int limit) {
        return jdbc.query("SELECT BIN_TO_UUID(t.uid) AS uid, t.nickname, t.rooms, t.created,"
                        + " RANK() OVER (ORDER BY t.rooms DESC) AS rnk FROM (" + roomsPerUser() + ") t"
                        + " ORDER BY t.rooms DESC, t.created DESC, t.nickname LIMIT ?",
                (rs, i) -> new AdminDtos.RoomRank(rs.getInt("rnk"), rs.getString("uid"), rs.getString("nickname"),
                        rs.getLong("rooms"), rs.getLong("created"), rs.getLong("rooms") - rs.getLong("created")),
                limit);
    }

    // ===== 묶음별 =====

    private List<AdminDtos.AgeGender> ageByGender(String fromUsers, LocalDate today) {
        Map<String, AdminDtos.AgeGender> rows = new LinkedHashMap<>();
        jdbc.query("SELECT " + decade(today) + " AS k,"
                        + " SUM(ui.gender <=> 'MALE') AS male, SUM(ui.gender <=> 'FEMALE') AS female,"
                        + " SUM(ui.gender <=> 'NON_BINARY') AS nb,"
                        + " SUM(ui.gender IS NULL OR ui.gender = 'PREFER_NOT_TO_SAY') AS undisclosed" + fromUsers + " GROUP BY k",
                rs -> {
                    rows.put(rs.getString("k"), new AdminDtos.AgeGender(rs.getString("k"), rs.getLong("male"),
                            rs.getLong("female"), rs.getLong("nb"), rs.getLong("undisclosed")));
                });
        return DECADES.stream().map(k -> rows.getOrDefault(k, new AdminDtos.AgeGender(k, 0, 0, 0, 0))).toList();
    }

    /** 동의는 항목마다 회원당 한 행이다. 개별 동의(위치·건강 정보)는 그 기능을 켤 때 생기므로 동의+미동의가 회원 수보다 작다. */
    private List<AdminDtos.Consent> consents() {
        Map<String, AdminDtos.Consent> rows = new LinkedHashMap<>();
        jdbc.query("SELECT s.agreement_type AS k, SUM(s.agreed = 1) AS yes, SUM(s.agreed = 0) AS no"
                        + " FROM user_agreement_states s JOIN users u ON u.id = s.user_id WHERE " + scope + " GROUP BY k",
                rs -> {
                    rows.put(rs.getString("k"), new AdminDtos.Consent(rs.getString("k"), rs.getLong("yes"), rs.getLong("no")));
                });
        return Arrays.stream(AgreementType.values()).map(Enum::name)
                .map(k -> rows.getOrDefault(k, new AdminDtos.Consent(k, 0, 0))).toList();
    }

    /** last_active_at 은 인증 API 호출 때 하루 한 번 갱신된다 — 1일 안은 「오늘·어제 앱을 연 사람」에 가깝다. */
    private AdminDtos.Activity activity(Instant now) {
        Map<String, Object> r = jdbc.queryForMap("SELECT SUM(u.last_active_at >= ?) AS d1, SUM(u.last_active_at >= ?) AS d3,"
                        + " SUM(u.last_active_at >= ?) AS d7 FROM users u WHERE " + scope,
                DbTime.utc(now.minus(Duration.ofDays(1))), DbTime.utc(now.minus(Duration.ofDays(3))),
                DbTime.utc(now.minus(Duration.ofDays(7))));
        return new AdminDtos.Activity(num(r.get("d1")), num(r.get("d3")), num(r.get("d7")));
    }

    /** 방 참여 경험은 지워진 방의 보관 이력까지 본다 — 방이 하드 삭제되면 멤버 행이 사라진다. */
    private AdminDtos.Engagement engagement() {
        Map<String, Object> r = jdbc.queryForMap("SELECT"
                + " SUM(EXISTS(SELECT 1 FROM challenge_members cm WHERE cm.user_id = u.id)"
                + "  OR EXISTS(SELECT 1 FROM challenge_member_history h WHERE h.user_id = u.id)) AS joined,"
                + " SUM(EXISTS(SELECT 1 FROM challenge_members cm WHERE cm.user_id = u.id AND cm.status = 'ACTIVE')) AS in_room,"
                + " SUM(EXISTS(SELECT 1 FROM challenge_members cm WHERE cm.user_id = u.id AND cm.role = 'OWNER')) AS owned,"
                + " SUM(EXISTS(SELECT 1 FROM VerificationDaily v WHERE v.userId = u.id AND v.status IN ('SUCCESS', 'FAILED'))) AS judged,"
                + " SUM(EXISTS(SELECT 1 FROM VerificationDaily v WHERE v.userId = u.id AND v.status = 'SUCCESS')) AS succeeded"
                + " FROM users u WHERE " + scope);
        return new AdminDtos.Engagement(num(r.get("joined")), num(r.get("in_room")), num(r.get("owned")),
                num(r.get("judged")), num(r.get("succeeded")));
    }

    /** 참여 중인 방이 있는 사람을 경로별로 — 방 개수가 아니라 사람 수다. 세 칸은 겹치지 않는다. */
    private AdminDtos.RoomParticipation participation() {
        Map<String, Object> r = jdbc.queryForMap("SELECT COUNT(*) AS n, SUM(t.created = t.rooms) AS created_only,"
                + " SUM(t.created = 0) AS joined_only, SUM(t.created > 0 AND t.created < t.rooms) AS both_paths"
                + " FROM (" + roomsPerUser() + ") t");
        return new AdminDtos.RoomParticipation(num(r.get("n")), num(r.get("created_only")), num(r.get("joined_only")),
                num(r.get("both_paths")),
                fixed(ROOM_COUNTS, buckets("SELECT IF(t.rooms >= 5, '5+', CAST(t.rooms AS CHAR)) AS k, COUNT(*) AS n"
                        + " FROM (" + roomsPerUser() + ") t GROUP BY k")));
    }

    /**
     * 회원별 「지금 참여 중인 방」 수와 그중 직접 만든 방 수. 시작 전·진행 중인 방의 ACTIVE 멤버십만 센다 — 끝난 방은 뺀다.
     *
     * <p>만든 방은 가입 사건({@code challenge_join_events})이 없는 멤버십이다. 가입 사건은 참여 경로(탐색 가입·초대 수락)에만
     * 쌓이고 방 생성은 멤버만 만든다. 그래서 방장을 넘겨받은 사람은 「들어간 방」, 넘겨준 사람은 여전히 「만든 방」이다.
     * 탐색과 초대는 같은 참여 경로라 가를 기록이 없어 「들어간 방」 하나로 센다.
     */
    private String roomsPerUser() {
        return "SELECT u.id AS uid, u.approved_nickname AS nickname, COUNT(*) AS rooms,"
                + " SUM(NOT EXISTS(SELECT 1 FROM challenge_join_events e"
                + "  WHERE e.challenge_id = cm.challenge_id AND e.user_id = cm.user_id)) AS created"
                + " FROM challenge_members cm JOIN challenges c ON c.id = cm.challenge_id JOIN users u ON u.id = cm.user_id"
                + " WHERE cm.status = 'ACTIVE' AND c.status IN ('UPCOMING', 'ACTIVE') AND c.deleted_at IS NULL AND " + scope
                + " GROUP BY u.id";
    }

    /** 탈퇴자는 회원 범위 밖이라 운영자·제외 계정만 빼고 탈퇴 상태를 본다. */
    private AdminDtos.Withdrawals withdrawals() {
        String withdrawn = everyone + " AND u.status = 'WITHDRAWN'";
        String lived = "TIMESTAMPDIFF(MINUTE, u.created_at, u.deleted_at)";
        Map<String, Object> r = jdbc.queryForMap("SELECT COUNT(*) AS n, SUM(" + lived + " < 60) AS h1,"
                + " SUM(" + lived + " >= 60 AND " + lived + " < 1440) AS d1,"
                + " SUM(" + lived + " >= 1440 AND " + lived + " < 10080) AS d7,"
                + " SUM(" + lived + " >= 10080) AS later FROM users u WHERE " + withdrawn);
        return new AdminDtos.Withdrawals(num(r.get("n")), num(r.get("h1")), num(r.get("d1")), num(r.get("d7")),
                num(r.get("later")));
    }

    // ===== 나이 식 =====

    /** 만 나이 식. 기준일은 LocalDate 문자열(yyyy-MM-dd)이라 그대로 넣어도 안전하고, 자리표시자 개수를 셀 일이 없다. */
    private static String age(LocalDate today) {
        return "TIMESTAMPDIFF(YEAR, ui.birth_date, '" + today + "')";
    }

    private static String decade(LocalDate today) {
        String age = age(today);
        return "CASE WHEN ui.birth_date IS NULL THEN 'UNKNOWN' WHEN " + age + " < 20 THEN '10'"
                + " WHEN " + age + " >= 50 THEN '50+' ELSE CAST(FLOOR(" + age + " / 10) * 10 AS CHAR) END";
    }

    private static String fiveYear(LocalDate today) {
        String age = age(today);
        return "CASE WHEN ui.birth_date IS NULL THEN 'UNKNOWN' WHEN " + age + " < 20 THEN '14-19'"
                + " WHEN " + age + " < 25 THEN '20-24' WHEN " + age + " < 30 THEN '25-29' WHEN " + age + " < 35 THEN '30-34'"
                + " WHEN " + age + " < 40 THEN '35-39' WHEN " + age + " < 50 THEN '40-49' ELSE '50+' END";
    }

    // ===== 내부 =====

    private Map<String, Long> buckets(String sql, Object... args) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString("k"), rs.getLong("n"));
        }, args);
        return out;
    }

    private static List<AdminDtos.Bucket> list(Map<String, Long> counts) {
        return counts.entrySet().stream().map(e -> new AdminDtos.Bucket(e.getKey(), e.getValue())).toList();
    }

    /** 고정 열거 칸을 0 으로 채워 정해진 순서로. 열거 밖의 값이 DB 에 있으면(옛 값) 뒤에 붙여 숫자가 사라지지 않게 한다. */
    private static List<AdminDtos.Bucket> fixed(List<String> keys, Map<String, Long> counts) {
        return Stream.concat(keys.stream(), counts.keySet().stream().filter(k -> !keys.contains(k)))
                .map(k -> new AdminDtos.Bucket(k, counts.getOrDefault(k, 0L))).toList();
    }

    private static List<String> names(Enum<?>[] values, String... extra) {
        return Stream.concat(Arrays.stream(values).map(Enum::name), Arrays.stream(extra)).toList();
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static Double dbl(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Integer integer(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }
}
