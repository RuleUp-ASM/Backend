package com.ruleup.ruleup_backend.observability.usage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 서비스 이용 게이지 — {@link UsageStatsService} 가 센 값을 CloudWatch 로(대시보드 ruleup-&lt;env&gt;-business ⑤).
 *
 * <ul>
 *   <li>{@code biz.rooms.count{mode,verification,status}} — 지금 있는 방 수. mode=group·solo, verification=auto·manual,
 *       status=upcoming·active (8칸)</li>
 *   <li>{@code biz.rooms.memberships{mode,verification}} — 그 방들의 참여 인원 합(방 × 사람). 방당 평균 인원 = 이것 / 방 수</li>
 *   <li>{@code biz.rooms.people_by_mode{mode}} · {@code biz.rooms.people_by_verification{verification}} — 참여 중인 사람 수.
 *       한 사람이 두 칸에 다 있을 수 있다(그룹과 솔로를 함께 하는 사람)</li>
 * </ul>
 *
 * <p>칸은 미리 다 만든다 — 열거 밖 값(옛 값)으로 새 지표가 생기면 과금 단위가 몰래 늘어난다. 태그 조합 하나가 CloudWatch
 * 지표 하나로 과금된다. 회원 구성({@link com.ruleup.ruleup_backend.observability.MemberProfileMetrics})처럼 10분마다
 * 세고, 읽기 전용이라 ShedLock 을 걸지 않는다. 대시보드는 Maximum 으로 본다.
 */
@Slf4j
@Component
public class UsageMetrics {

    static final List<String> MODES = List.of("group", "solo");
    static final List<String> VERIFICATIONS = List.of("auto", "manual");
    static final List<String> ROOM_STATUSES = List.of("upcoming", "active");

    private final UsageStatsService stats;
    /** 이름 + 태그 값으로 찾는 게이지 칸 — {@link #key}. */
    private final Map<String, AtomicLong> cells = new HashMap<>();
    private final MeterRegistry registry;

    public UsageMetrics(UsageStatsService stats, MeterRegistry registry) {
        this.stats = stats;
        this.registry = registry;
        for (String mode : MODES) {
            for (String v : VERIFICATIONS) {
                for (String st : ROOM_STATUSES)
                    register("biz.rooms.count", "지금 있는 방 수 — 모드·인증 방식·상태별",
                            "mode", mode, "verification", v, "status", st);
                register("biz.rooms.memberships", "지금 있는 방의 참여 인원 합(방 × 사람) — 모드·인증 방식별",
                        "mode", mode, "verification", v);
            }
            register("biz.rooms.people_by_mode", "그룹·솔로 방에 참여 중인 사람 수(겹칠 수 있다)", "mode", mode);
        }
        for (String v : VERIFICATIONS)
            register("biz.rooms.people_by_verification", "자동·수동 인증 방에 참여 중인 사람 수(겹칠 수 있다)", "verification", v);
    }

    /** tags 는 (키, 값) 쌍을 이어 쓴다. 칸은 이름과 값들로 찾는다({@link #key}). */
    private void register(String name, String description, String... tags) {
        AtomicLong holder = new AtomicLong();
        String[] values = new String[tags.length / 2];
        Gauge.Builder<AtomicLong> b = Gauge.builder(name, holder, AtomicLong::doubleValue).description(description);
        for (int i = 0; i < tags.length; i += 2) {
            b.tag(tags[i], tags[i + 1]);
            values[i / 2] = tags[i + 1];
        }
        b.register(registry);
        cells.put(key(name, values), holder);
    }

    private static String key(String name, String... values) {
        return name + "|" + String.join("|", values);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void refresh() {
        try {
            applyRooms(stats.rooms());
        } catch (RuntimeException e) {
            // 지표 갱신 실패가 아무것도 막으면 안 된다. 다음 주기가 다시 센다.
            log.warn("방 구성 지표 갱신 실패: {}", e.toString());
        }
    }

    void applyRooms(UsageStatsService.RoomComposition r) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> memberships = new HashMap<>();
        for (var c : r.cells()) {
            String mode = tag(c.mode());
            String v = tag(c.verification());
            counts.merge(key("biz.rooms.count", mode, v, tag(c.status())), c.rooms(), Long::sum);
            memberships.merge(key("biz.rooms.memberships", mode, v), c.memberships(), Long::sum);
        }
        // 이번에 안 나온 칸은 0 — 방이 다 끝나면 지난 값이 남지 않게.
        for (String mode : MODES) {
            for (String v : VERIFICATIONS) {
                for (String st : ROOM_STATUSES) {
                    String k = key("biz.rooms.count", mode, v, st);
                    set(k, counts.getOrDefault(k, 0L));
                }
                String k = key("biz.rooms.memberships", mode, v);
                set(k, memberships.getOrDefault(k, 0L));
            }
        }
        set(key("biz.rooms.people_by_mode", "group"), r.people().group());
        set(key("biz.rooms.people_by_mode", "solo"), r.people().solo());
        set(key("biz.rooms.people_by_verification", "auto"), r.people().auto());
        set(key("biz.rooms.people_by_verification", "manual"), r.people().manual());
    }

    /** 미리 만든 칸만 채운다. */
    private void set(String key, long value) {
        AtomicLong holder = cells.get(key);
        if (holder != null) holder.set(value);
    }

    private static String tag(String v) {
        return v == null ? "" : v.toLowerCase(Locale.ROOT);
    }
}
