package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.admin.stats.MemberScope;
import com.ruleup.ruleup_backend.common.DbTime;
import com.ruleup.ruleup_backend.score.domain.Tier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서비스 이용 지표 — 방 구성(그룹·솔로 × 자동·수동)·자동 인증 진행·관심사와 루틴·티어·탈퇴 단계·화면 조회를 「지금」 기준으로 센다. 게이지 {@link UsageMetrics} 가 쓴다.
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

    /** 진행 중인 자동 인증 방의 ACTIVE 멤버십. */
    private static final String LIVE_AUTO_MEMBERSHIPS = " FROM challenge_members cm JOIN challenges c ON c.id = cm.challenge_id"
            + " JOIN users u ON u.id = cm.user_id"
            + " WHERE cm.status = 'ACTIVE' AND c.status = 'ACTIVE' AND c.deleted_at IS NULL AND " + VERIFICATION + " = 'AUTO' AND ";
    /** 확정이 끝난 귀속일 몇 날을 볼지 — 그제부터 거꾸로 7일. */
    static final int JUDGED_DAYS = 7;
    /** 「최근 sync 가 있다」의 폭 — sync 주기(최대 몇 시간)보다 넉넉하게 하루. */
    static final Duration SYNC_FRESH = Duration.ofDays(1);
    public static final List<String> STALL_REASONS = List.of(
            "ok", "pending_setup", "permission_missing", "just_joined", "never_synced", "sync_stale");

    public static final List<String> INTEREST_FIT = List.of("interest_only", "mixed", "outside_only", "no_interest");
    public static final List<String> WITHDRAWAL_STAGES = List.of("before_room", "no_judgment", "after_judgment");

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String scope;
    private final String everyone;

    public UsageStatsService(JdbcTemplate jdbc, Clock clock,
                             @Value("${app.admin.stats-excluded-user-ids:}") String excludedUserIds) {
        this.jdbc = jdbc;
        this.clock = clock;
        MemberScope range = MemberScope.of(excludedUserIds);
        this.scope = range.members();
        this.everyone = range.everyone();
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

    /**
     * 자동 인증 진행.
     *
     * @param inRoom        진행 중인 자동 인증 방에 참여 중인 사람
     * @param synced1d      그중 최근 1일 안에 앱이 sync 를 보낸 사람(신호를 보내고 있는 사람)
     * @param synced7d      최근 7일 안
     * @param stall         진행 중인 자동 인증 방의 멤버십(방 × 사람)을 진행 상태로 — {@link #STALL_REASONS}, 겹치지 않는다
     * @param judgments     확정된 최근 {@value #JUDGED_DAYS}일 판정 — success·failed_measured(신호는 왔는데 목표 미달)·
     *                      failed_no_signal·failed_permission
     * @param succeededPeople  같은 기간에 신호로 측정된 판정이 있는 사람 중 한 번이라도 성공한 사람
     * @param failedOnlyPeople 측정된 판정이 실패뿐인 사람
     */
    public record AutoVerification(long inRoom, long synced1d, long synced7d, Map<String, Long> stall,
                                   Map<String, Long> judgments, long succeededPeople, long failedOnlyPeople) {}

    /**
     * 자동 인증 방에 들어간 사람이 실제로 인증을 굴리고 있는가.
     *
     * <p>「신호를 보낸다」는 {@code challenge_members.last_synced_at}(sync 가 그 방 판정을 갱신한 시각)으로 본다 — 신호 원본은
     * 도메인별 파티션 표 셋에 흩어져 있고 보관 기간이 지나면 지워져 사람 수를 세기에 맞지 않는다. sync 깨우기 푸시
     * ({@code SyncWakePushService})가 「앱이 살아 있다」를 보는 기준과 같다.
     *
     * <p>진행 상태는 앞에서부터 처음 맞는 하나로 정한다.
     * <ol>
     *   <li>{@code pending_setup} — 장소·앱 고르기 같은 첫 셋업을 안 마쳐 판정을 건너뛴다</li>
     *   <li>{@code permission_missing} — 권한이 없어 측정이 안 되는 상태가 풀리지 않았다</li>
     *   <li>{@code ok} — 최근 1일 안에 sync 가 들어왔다</li>
     *   <li>{@code just_joined} — sync 가 한 번도 없지만 들어온 지 1일이 안 됐다(판정은 다음 날부터)</li>
     *   <li>{@code never_synced} — 들어온 지 1일이 넘었는데 이 방으로 sync 가 한 번도 없다</li>
     *   <li>{@code sync_stale} — sync 가 있었지만 1일 넘게 끊겼다</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public AutoVerification autoVerification() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KST);
        Object fresh = DbTime.utc(now.minus(SYNC_FRESH));

        Map<String, Object> people = jdbc.queryForMap("SELECT COUNT(DISTINCT cm.user_id) AS in_room,"
                        + " COUNT(DISTINCT IF(cm.last_synced_at >= ?, cm.user_id, NULL)) AS d1,"
                        + " COUNT(DISTINCT IF(cm.last_synced_at >= ?, cm.user_id, NULL)) AS d7"
                        + LIVE_AUTO_MEMBERSHIPS + scope,
                fresh, DbTime.utc(now.minus(Duration.ofDays(7))));

        Map<String, Long> stall = new LinkedHashMap<>();
        STALL_REASONS.forEach(r -> stall.put(r, 0L));
        jdbc.query("SELECT CASE WHEN cm.setup_status = 'PENDING_SETUP' THEN 'pending_setup'"
                        + " WHEN EXISTS(SELECT 1 FROM verification_permission_waits w WHERE w.challenge_id = cm.challenge_id"
                        + "  AND w.user_id = cm.user_id AND w.resolved_at IS NULL) THEN 'permission_missing'"
                        + " WHEN cm.last_synced_at >= ? THEN 'ok'"
                        + " WHEN cm.last_synced_at IS NULL AND COALESCE(cm.participation_started_at, cm.joined_at) >= ? THEN 'just_joined'"
                        + " WHEN cm.last_synced_at IS NULL THEN 'never_synced'"
                        + " ELSE 'sync_stale' END AS k, COUNT(*) AS n" + LIVE_AUTO_MEMBERSHIPS + scope + " GROUP BY k",
                rs -> {
                    stall.put(rs.getString("k"), rs.getLong("n"));
                }, fresh, fresh);

        // 확정이 끝난 귀속일 — 그제(오늘 00:00 KST 에 확정)부터 거꾸로 JUDGED_DAYS 일.
        LocalDate last = today.minusDays(2);
        LocalDate first = last.minusDays(JUDGED_DAYS - 1);
        String judged = " FROM VerificationDaily d JOIN challenges c ON c.id = d.challengeId JOIN users u ON u.id = d.userId"
                + " WHERE d.targetDate BETWEEN ? AND ? AND d.status IN ('SUCCESS', 'FAILED') AND " + VERIFICATION + " = 'AUTO'"
                + " AND " + scope;
        Map<String, Object> j = jdbc.queryForMap("SELECT SUM(d.status = 'SUCCESS') AS s,"
                + " SUM(d.status = 'FAILED' AND d.gapReason IS NULL) AS fm,"
                + " SUM(d.status = 'FAILED' AND d.gapReason = 'NO_SIGNAL') AS fn,"
                + " SUM(d.status = 'FAILED' AND d.gapReason = 'PERMISSION_MISSING') AS fp" + judged, first, last);
        Map<String, Long> judgments = new LinkedHashMap<>();
        judgments.put("success", num(j.get("s")));
        judgments.put("failed_measured", num(j.get("fm")));
        judgments.put("failed_no_signal", num(j.get("fn")));
        judgments.put("failed_permission", num(j.get("fp")));

        // 신호로 측정된 판정(성공, 또는 판정 불가가 아닌 실패)이 있는 사람만 — 신호를 보낸 사람 중 성공·실패.
        Map<String, Object> p = jdbc.queryForMap("SELECT SUM(t.s > 0) AS ok, SUM(t.s = 0) AS ng FROM ("
                + "SELECT d.userId, SUM(d.status = 'SUCCESS') AS s" + judged
                + " AND (d.status = 'SUCCESS' OR d.gapReason IS NULL) GROUP BY d.userId) t", first, last);

        return new AutoVerification(num(people.get("in_room")), num(people.get("d1")), num(people.get("d7")),
                stall, judgments, num(p.get("ok")), num(p.get("ng")));
    }

    /**
     * 회원 쪽 분포.
     *
     * @param tiers           표시 티어별 회원 수 — {@link Tier} 순서, 점수 행이 없으면 UNRANKED
     * @param interestFit     참여 중인 방(시작 전·진행 중)이 있는 사람을 「방 카테고리가 고른 관심사 안인가」로 —
     *                        interest_only(전부 관심사 안)·mixed(안과 밖이 섞임)·outside_only(전부 밖)·no_interest(관심사를 안 고름)
     * @param withdrawalStage 탈퇴자가 어디까지 써 보고 나갔나 — before_room(방에 한 번도 안 들어감)·no_judgment(방엔 들어갔지만
     *                        확정 판정 없음)·after_judgment(판정까지 받아 봄)
     */
    public record MemberUsage(Map<String, Long> tiers, Map<String, Long> interestFit, Map<String, Long> withdrawalStage) {}

    @Transactional(readOnly = true)
    public MemberUsage members() {
        Map<String, Long> tiers = new LinkedHashMap<>();
        Arrays.stream(Tier.values()).forEach(t -> tiers.put(t.name(), 0L));
        jdbc.query("SELECT IFNULL(s.display_tier, 'UNRANKED') AS k, COUNT(*) AS n FROM users u"
                        + " LEFT JOIN user_score_summaries s ON s.user_id = u.id WHERE " + scope + " GROUP BY k",
                rs -> {
                    tiers.merge(rs.getString("k"), rs.getLong("n"), Long::sum);
                });

        // 사람마다 참여 중인 방 수(rooms)와 그중 카테고리가 관심사 안인 방 수(inside), 고른 관심사 수(picked).
        Map<String, Object> f = jdbc.queryForMap("SELECT SUM(t.picked = 0) AS none,"
                + " SUM(t.picked > 0 AND t.inside = t.rooms) AS only_in,"
                + " SUM(t.picked > 0 AND t.inside = 0) AS only_out,"
                + " SUM(t.picked > 0 AND t.inside > 0 AND t.inside < t.rooms) AS mixed FROM ("
                + "SELECT cm.user_id, COUNT(*) AS rooms,"
                + " SUM(EXISTS(SELECT 1 FROM user_interests i WHERE i.user_id = cm.user_id AND i.category = c.category)) AS inside,"
                + " MAX((SELECT COUNT(*) FROM user_interests i2 WHERE i2.user_id = cm.user_id)) AS picked"
                + LIVE_MEMBERSHIPS + scope + " GROUP BY cm.user_id) t");
        Map<String, Long> fit = new LinkedHashMap<>();
        fit.put("interest_only", num(f.get("only_in")));
        fit.put("mixed", num(f.get("mixed")));
        fit.put("outside_only", num(f.get("only_out")));
        fit.put("no_interest", num(f.get("none")));

        // 탈퇴해도 멤버십(LEFT)·판정 행은 남는다. 지워진 방의 멤버십은 보관 이력에서 찾는다.
        String joined = "(EXISTS(SELECT 1 FROM challenge_members cm WHERE cm.user_id = u.id)"
                + " OR EXISTS(SELECT 1 FROM challenge_member_history h WHERE h.user_id = u.id))";
        String judged = "EXISTS(SELECT 1 FROM VerificationDaily v WHERE v.userId = u.id AND v.status IN ('SUCCESS', 'FAILED'))";
        Map<String, Object> w = jdbc.queryForMap("SELECT SUM(NOT " + joined + " AND NOT " + judged + ") AS before_room,"
                + " SUM(" + joined + " AND NOT " + judged + ") AS no_judgment, SUM(" + judged + ") AS after_judgment"
                + " FROM users u WHERE " + everyone + " AND u.status = 'WITHDRAWN'");
        Map<String, Long> stage = new LinkedHashMap<>();
        WITHDRAWAL_STAGES.forEach(k -> stage.put(k, num(w.get(k))));

        return new MemberUsage(tiers, fit, stage);
    }

    /** 화면별 본 사람 수 — 오늘(KST)과 오늘 포함 최근 7일. {@link ScreenViewRecorder#SCREEN_NAMES} 칸을 0 으로 채운다. */
    public record ScreenViewers(Map<String, Long> today, Map<String, Long> last7d) {}

    @Transactional(readOnly = true)
    public ScreenViewers screens() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        Map<String, Long> day = new LinkedHashMap<>();
        Map<String, Long> week = new LinkedHashMap<>();
        ScreenViewRecorder.SCREEN_NAMES.forEach(k -> {
            day.put(k, 0L);
            week.put(k, 0L);
        });
        jdbc.query("SELECT v.screen AS k, COUNT(DISTINCT IF(v.view_date = ?, v.user_id, NULL)) AS d1,"
                        + " COUNT(DISTINCT v.user_id) AS d7 FROM user_screen_views v JOIN users u ON u.id = v.user_id"
                        + " WHERE v.view_date >= ? AND " + scope + " GROUP BY k",
                rs -> {
                    day.put(rs.getString("k"), rs.getLong("d1"));
                    week.put(rs.getString("k"), rs.getLong("d7"));
                }, today, today.minusDays(6));
        return new ScreenViewers(day, week);
    }

    static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
