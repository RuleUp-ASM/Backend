package com.ruleup.ruleup_backend.observability.usage;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「이 회원이 오늘 이 화면을 열었다」를 {@code user_screen_views} 에 한 번 남긴다 — {@link ScreenViewInterceptor} 가 부른다.
 *
 * <p>요청 경로에 쓰기를 얹는 일이라 두 겹으로 가볍게 한다.
 * <ul>
 *   <li>태스크 메모리에 오늘 남긴 (화면, 회원)을 들고 있다가 같은 날 두 번째부터는 DB 에 가지 않는다. 날짜가 바뀌면 비운다.
 *       태스크가 여럿이면 태스크마다 한 번씩 갈 수 있지만 {@code INSERT IGNORE} 라 행은 하나다.</li>
 *   <li>실패는 삼킨다 — 지표 때문에 화면이 실패하면 안 된다. 메모리에 먼저 적고 DB 에 가므로 실패해도 같은 날 다시
 *       시도하지 않는다(DB 장애 때 요청마다 쓰기를 쏟지 않게). 그 회원·화면의 그날 기록이 빠질 뿐이다.</li>
 * </ul>
 */
@Slf4j
@Component
public class ScreenViewRecorder {

    /** 메모리 상한 — 넘으면 비운다(다시 쌓이는 동안 INSERT IGNORE 가 몇 번 더 갈 뿐이다). */
    static final int MAX_REMEMBERED = 200_000;
    static final int RETENTION_DAYS = 35;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile LocalDate day;
    private volatile Set<String> seen = ConcurrentHashMap.newKeySet();

    public ScreenViewRecorder(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(UUID userId, String screen) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        Set<String> todaySeen = rememberedFor(today);
        if (!todaySeen.add(screen + ":" + userId)) return;
        try {
            jdbc.update("INSERT IGNORE INTO user_screen_views (view_date, screen, user_id) VALUES (?, ?, ?)",
                    today, screen, bytes(userId));
        } catch (RuntimeException e) {
            log.warn("screen_view_record_failed screen={} cause={}", screen, e.toString());
        }
    }

    private Set<String> rememberedFor(LocalDate today) {
        if (!today.equals(day) || seen.size() > MAX_REMEMBERED) {
            synchronized (this) {
                if (!today.equals(day) || seen.size() > MAX_REMEMBERED) {
                    seen = ConcurrentHashMap.newKeySet();
                    day = today;
                }
            }
        }
        return seen;
    }

    /** 보관 기간이 지난 행 — 하루 한 번, 한 태스크만. */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "ScreenViewRecorder.purge", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            LocalDate cutoff = LocalDate.now(clock.withZone(KST)).minusDays(RETENTION_DAYS);
            int deleted;
            do {
                deleted = jdbc.update("DELETE FROM user_screen_views WHERE view_date < ? LIMIT 5000", cutoff);
            } while (deleted == 5000);
        } catch (RuntimeException e) {
            log.warn("screen_view_purge_failed cause={}", e.toString());
        }
    }

    /** 시험용 — 메모리 기록을 비운다. */
    void forget() {
        seen = ConcurrentHashMap.newKeySet();
    }

    private static byte[] bytes(UUID u) {
        return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }

    /** 게이지 칸 순서. home 은 「앱을 연 사람」— 다른 화면 비율의 분모로 쓴다. */
    public static final List<String> SCREEN_NAMES = List.of(
            "home", "room_home", "feed", "room_ranking", "cross_ranking", "stats", "calendar", "tier", "members");

    /**
     * 그 화면을 그리는 GET API 경로 패턴(스프링이 매칭한 패턴 문자열 그대로) → 화면 이름.
     * 서버는 화면이 아니라 API 호출을 본다 — 앱이 다른 화면에서 미리 불러 두는 API 라면 그만큼 크게 잡힌다.
     */
    static final Map<String, String> SCREENS = Map.ofEntries(
            Map.entry("/api/v1/me/home", "home"),
            Map.entry("/api/v1/challenges/{challengeId}/room", "room_home"),
            Map.entry("/api/v1/challenges/{challengeId}/threads", "feed"),
            Map.entry("/api/v1/challenges/{challengeId}/ranking", "room_ranking"),
            Map.entry("/api/v1/rankings/challenges", "cross_ranking"),
            Map.entry("/api/v1/me/stats", "stats"),
            Map.entry("/api/v1/me/calendar", "calendar"),
            Map.entry("/api/v1/me/calendar/{date}", "calendar"),
            Map.entry("/api/v1/challenges/{challengeId}/calendar", "calendar"),
            Map.entry("/api/v1/me/tier", "tier"),
            Map.entry("/api/v1/me/tier/changes", "tier"),
            Map.entry("/api/v1/me/tier/history", "tier"),
            Map.entry("/api/v1/challenges/{challengeId}/members", "members"));
}
