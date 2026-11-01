package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.observability.CloudWatchMetricsConfigAccess;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.AutoVerification;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.MemberUsage;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomCell;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomComposition;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomPeople;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageMetricsTest {

    private final UsageStatsService stats = mock(UsageStatsService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UsageMetrics metrics = new UsageMetrics(stats, registry);

    @BeforeEach
    void stub() {
        when(stats.rooms()).thenReturn(rooms());
        when(stats.autoVerification()).thenReturn(auto());
        when(stats.members()).thenReturn(new MemberUsage(
                Map.of("UNRANKED", 1L, "BRONZE", 20L, "SILVER", 4L, "GOLD", 0L, "DIAMOND", 0L, "RUBY", 0L, "LEGACY", 9L),
                Map.of("interest_only", 5L, "mixed", 2L, "outside_only", 1L, "no_interest", 3L),
                Map.of("before_room", 4L, "no_judgment", 1L, "after_judgment", 1L)));
    }

    private static AutoVerification auto() {
        Map<String, Long> stall = new LinkedHashMap<>();
        UsageStatsService.STALL_REASONS.forEach(r -> stall.put(r, 0L));
        stall.put("ok", 7L);
        stall.put("pending_setup", 3L);
        stall.put("never_synced", 2L);
        return new AutoVerification(11, 8, 9, stall,
                Map.of("success", 40L, "failed_measured", 10L, "failed_no_signal", 5L, "failed_permission", 1L), 6, 2);
    }

    private static RoomComposition rooms() {
        return new RoomComposition(List.of(
                new RoomCell("GROUP", "AUTO", "ACTIVE", 3, 12),
                new RoomCell("GROUP", "AUTO", "UPCOMING", 1, 2),
                new RoomCell("SOLO", "MANUAL", "ACTIVE", 5, 5),
                new RoomCell("SOLO", null, "ACTIVE", 1, 1)),          // 방식 미상 — 칸이 없어 버린다
                new RoomPeople(10, 6, 11, 5, 14));
    }

    private double gauge(String name, String... tags) {
        var search = registry.get(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.gauge().value();
    }

    @Test
    @DisplayName("방 구성 — 모드·인증 방식·상태 칸, 참여 인원은 상태를 합친다, 사람 수는 겹쳐 센다")
    void roomGauges() {
        metrics.refresh();

        assertThat(gauge("biz.rooms.count", "mode", "group", "verification", "auto", "status", "active")).isEqualTo(3);
        assertThat(gauge("biz.rooms.count", "mode", "group", "verification", "auto", "status", "upcoming")).isEqualTo(1);
        assertThat(gauge("biz.rooms.count", "mode", "solo", "verification", "manual", "status", "active")).isEqualTo(5);
        assertThat(gauge("biz.rooms.count", "mode", "group", "verification", "manual", "status", "active"))
                .as("집계에 없던 칸은 0").isZero();
        assertThat(gauge("biz.rooms.memberships", "mode", "group", "verification", "auto")).isEqualTo(14);
        assertThat(gauge("biz.rooms.memberships", "mode", "solo", "verification", "manual")).isEqualTo(5);
        assertThat(gauge("biz.rooms.people_by_mode", "mode", "group")).isEqualTo(10);
        assertThat(gauge("biz.rooms.people_by_mode", "mode", "solo")).isEqualTo(6);
        assertThat(gauge("biz.rooms.people_by_verification", "verification", "auto")).isEqualTo(11);
        assertThat(gauge("biz.rooms.people_by_verification", "verification", "manual")).isEqualTo(5);
    }

    @Test
    @DisplayName("자동 인증 — sync 보낸 사람·진행 상태·판정 결과·성공 경험")
    void autoGauges() {
        metrics.refresh();

        assertThat(gauge("biz.auto.people", "state", "in_room")).isEqualTo(11);
        assertThat(gauge("biz.auto.people", "state", "synced_1d")).isEqualTo(8);
        assertThat(gauge("biz.auto.stall", "reason", "pending_setup")).isEqualTo(3);
        assertThat(gauge("biz.auto.stall", "reason", "sync_stale")).isZero();
        assertThat(gauge("biz.auto.judgments", "result", "failed_measured")).isEqualTo(10);
        assertThat(gauge("biz.auto.judged_people", "result", "failed_only")).isEqualTo(2);
    }

    @Test
    @DisplayName("회원 이용 — 티어(열거 밖 값은 버린다)·관심사 안팎·탈퇴 단계")
    void memberGauges() {
        metrics.refresh();

        assertThat(gauge("biz.members.tier", "tier", "bronze")).isEqualTo(20);
        assertThat(gauge("biz.members.tier", "tier", "unranked")).isEqualTo(1);
        assertThat(registry.find("biz.members.tier").tag("tier", "legacy").gauge()).isNull();
        assertThat(gauge("biz.members.interest_fit", "fit", "mixed")).isEqualTo(2);
        assertThat(gauge("biz.members.withdrawn_stage", "stage", "before_room")).isEqualTo(4);
    }

    @Test
    @DisplayName("한 묶음 집계가 실패해도 다른 묶음은 갱신된다")
    void sectionsFailIndependently() {
        when(stats.rooms()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();

        assertThat(gauge("biz.auto.people", "state", "in_room")).isEqualTo(11);
    }

    @Test
    @DisplayName("지난 갱신에 있던 방이 사라지면 칸이 0 으로 돌아간다")
    void resetsVanishedCells() {
        metrics.refresh();
        when(stats.rooms()).thenReturn(new RoomComposition(List.of(), new RoomPeople(0, 0, 0, 0, 0)));
        metrics.refresh();

        assertThat(gauge("biz.rooms.count", "mode", "group", "verification", "auto", "status", "active")).isZero();
        assertThat(gauge("biz.rooms.memberships", "mode", "group", "verification", "auto")).isZero();
    }

    @Test
    @DisplayName("지표 수는 고정 — 열거 밖 값이 와도 새 지표가 생기지 않는다")
    void fixedMeterCount() {
        metrics.refresh();
        assertThat(registry.getMeters()).hasSize(UsageMetricsTest.EXPECTED_METERS);
    }

    @Test
    @DisplayName("만드는 게이지는 전부 CloudWatch 허용 목록에 있다 — 빠지면 대시보드 칸이 조용히 비어 있다")
    void everyGaugeIsExported() {
        assertThat(registry.getMeters()).allSatisfy(m ->
                assertThat(CloudWatchMetricsConfigAccess.exported()).as("허용 목록").contains(m.getId().getName()));
    }

    @Test
    @DisplayName("집계가 실패해도 예외를 밖으로 내지 않고 지난 값을 둔다")
    void keepsLastValuesOnFailure() {
        metrics.refresh();
        when(stats.rooms()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();

        assertThat(gauge("biz.rooms.people_by_mode", "mode", "group")).isEqualTo(10);
    }

    /** 방 8 + 참여 인원 4 + 사람 수 2+2 · 자동 인증 3+6+4+2 · 티어 6 + 관심사 4 + 탈퇴 단계 3. */
    static final int EXPECTED_METERS = 44;
}
