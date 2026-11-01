package com.ruleup.ruleup_backend.observability.usage;

import com.ruleup.ruleup_backend.observability.CloudWatchMetricsConfigAccess;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomCell;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomComposition;
import com.ruleup.ruleup_backend.observability.usage.UsageStatsService.RoomPeople;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** 방 8 + 참여 인원 4 + 사람 수 2+2. */
    static final int EXPECTED_METERS = 16;
}
