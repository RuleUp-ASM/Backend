package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.score.domain.Tier;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
 *   <li>{@code biz.auto.people{state}} — 진행 중인 자동 인증 방에 참여 중인 사람(in_room)과 그중 최근 1일·7일 안에
 *       sync 를 보낸 사람(synced_1d·synced_7d) — 「자동 인증을 실제로 하고 있는 사람」</li>
 *   <li>{@code biz.auto.stall{reason}} — 그 방들의 멤버십(방 × 사람)을 진행 상태로: ok·pending_setup·permission_missing·
 *       just_joined·never_synced·sync_stale (겹치지 않는다, {@link UsageStatsService#autoVerification})</li>
 *   <li>{@code biz.auto.judgments{result}} — 확정된 최근 7일 자동 인증 판정: success·failed_measured(신호는 왔는데 목표 미달)·
 *       failed_no_signal·failed_permission</li>
 *   <li>{@code biz.auto.judged_people{result}} — 그 기간 신호로 측정된 판정이 있는 사람 중 한 번이라도 성공(succeeded)·
 *       실패뿐(failed_only)</li>
 *   <li>{@code biz.members.tier{tier}} — 표시 티어별 회원 수(unranked·bronze·silver·gold·diamond·ruby)</li>
 *   <li>{@code biz.members.interest_fit{fit}} — 참여 중인 사람을 방 카테고리가 관심사 안인가로: interest_only·mixed·
 *       outside_only·no_interest. 「관심사 밖 루틴도 하는 사람」= mixed + outside_only</li>
 *   <li>{@code biz.members.withdrawn_stage{stage}} — 탈퇴자가 어디까지 써 보고 나갔나: before_room·no_judgment·after_judgment.
 *       가입 후 얼마 만에 나갔나는 {@code biz.members.withdrawn{lived}}(MemberProfileMetrics)</li>
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
    static final List<String> AUTO_PEOPLE = List.of("in_room", "synced_1d", "synced_7d");
    static final List<String> JUDGMENTS = List.of("success", "failed_measured", "failed_no_signal", "failed_permission");
    static final List<String> JUDGED_PEOPLE = List.of("succeeded", "failed_only");

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
        for (String st : AUTO_PEOPLE)
            register("biz.auto.people", "진행 중인 자동 인증 방의 사람 수와 그중 최근 sync 를 보낸 사람", "state", st);
        for (String r : UsageStatsService.STALL_REASONS)
            register("biz.auto.stall", "진행 중인 자동 인증 방의 멤버십 — 진행 상태별", "reason", r);
        for (String r : JUDGMENTS)
            register("biz.auto.judgments", "확정된 최근 7일 자동 인증 판정 — 결과별", "result", r);
        for (String r : JUDGED_PEOPLE)
            register("biz.auto.judged_people", "최근 7일 신호로 측정된 판정이 있는 사람 — 성공 경험 여부", "result", r);
        for (String t : Arrays.stream(Tier.values()).map(t -> tag(t.name())).toList())
            register("biz.members.tier", "표시 티어별 회원 수", "tier", t);
        for (String f : UsageStatsService.INTEREST_FIT)
            register("biz.members.interest_fit", "참여 중인 사람 — 방 카테고리가 고른 관심사 안인가", "fit", f);
        for (String st : UsageStatsService.WITHDRAWAL_STAGES)
            register("biz.members.withdrawn_stage", "탈퇴자 — 어디까지 써 보고 나갔나", "stage", st);
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
        // 묶음마다 따로 — 한 쿼리가 실패해도 나머지 칸은 갱신된다.
        run("방 구성", () -> applyRooms(stats.rooms()));
        run("자동 인증", () -> applyAuto(stats.autoVerification()));
        run("회원 이용", () -> applyMembers(stats.members()));
    }

    void applyMembers(UsageStatsService.MemberUsage m) {
        m.tiers().forEach((t, n) -> set(key("biz.members.tier", tag(t)), n));
        m.interestFit().forEach((f, n) -> set(key("biz.members.interest_fit", f), n));
        m.withdrawalStage().forEach((st, n) -> set(key("biz.members.withdrawn_stage", st), n));
    }

    private static void run(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 지표 갱신 실패가 아무것도 막으면 안 된다. 다음 주기가 다시 센다.
            log.warn("{} 지표 갱신 실패: {}", what, e.toString());
        }
    }

    void applyAuto(UsageStatsService.AutoVerification a) {
        set(key("biz.auto.people", "in_room"), a.inRoom());
        set(key("biz.auto.people", "synced_1d"), a.synced1d());
        set(key("biz.auto.people", "synced_7d"), a.synced7d());
        a.stall().forEach((r, n) -> set(key("biz.auto.stall", r), n));
        a.judgments().forEach((r, n) -> set(key("biz.auto.judgments", r), n));
        set(key("biz.auto.judged_people", "succeeded"), a.succeededPeople());
        set(key("biz.auto.judged_people", "failed_only"), a.failedOnlyPeople());
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
