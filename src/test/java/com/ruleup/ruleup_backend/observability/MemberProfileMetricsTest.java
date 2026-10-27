package com.ruleup.ruleup_backend.observability;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos.Bucket;
import com.ruleup.ruleup_backend.admin.stats.MemberProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberProfileMetricsTest {

    private final MemberProfileService service = mock(MemberProfileService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MemberProfileMetrics metrics = new MemberProfileMetrics(service, registry);

    private double gauge(String name, String key, String value) {
        return registry.get(name).tag(key, value).gauge().value();
    }

    private static AdminDtos.MemberProfile profile() {
        return new AdminDtos.MemberProfile("2026-10-27", 32,
                new AdminDtos.AgeSummary(36.3, 15, 56, 0),
                List.of(new Bucket("10", 5), new Bucket("20", 1), new Bucket("30", 14), new Bucket("40", 7),
                        new Bucket("50+", 5), new Bucket("UNKNOWN", 0)),
                List.of(),
                List.of(new Bucket("MALE", 16), new Bucket("FEMALE", 14), new Bucket("NON_BINARY", 0),
                        new Bucket("PREFER_NOT_TO_SAY", 1), new Bucket("UNKNOWN", 1)),
                List.of(),
                List.of(new Bucket("KAKAO", 20), new Bucket("NAVER", 0), new Bucket("GOOGLE", 12), new Bucket("APPLE", 0)),
                List.of(new Bucket("ANDROID", 32)), List.of(new Bucket("1.0.2", 30)), List.of(), List.of(),
                List.of(new Bucket("EXERCISE", 26), new Bucket("WAKE_SLEEP", 25), new Bucket("LEGACY_CATEGORY", 3)),
                List.of(),
                List.of(new AdminDtos.Consent("TOS", 32, 0), new AdminDtos.Consent("MARKETING", 9, 23),
                        new AdminDtos.Consent("EVENT", 8, 24)),
                new AdminDtos.Activity(7, 21, 32),
                new AdminDtos.Engagement(9, 7, 4, 3, 0),
                new AdminDtos.Withdrawals(6, 4, 1, 1, 0));
    }

    @Test
    @DisplayName("콘솔 집계를 고정 칸 게이지로 옮긴다 — 성별 미입력은 밝히지 않음에 합친다")
    void mapsSnapshotToGauges() {
        when(service.snapshot()).thenReturn(profile());
        metrics.refresh();

        assertThat(registry.get("biz.members.total").gauge().value()).isEqualTo(32);
        assertThat(gauge("biz.members.age", "band", "30s")).isEqualTo(14);
        assertThat(gauge("biz.members.age", "band", "50s_plus")).isEqualTo(5);
        assertThat(gauge("biz.members.age", "band", "unknown")).isZero();
        assertThat(gauge("biz.members.gender", "gender", "female")).isEqualTo(14);
        assertThat(gauge("biz.members.gender", "gender", "undisclosed")).isEqualTo(2);
        assertThat(gauge("biz.members.provider", "provider", "google")).isEqualTo(12);
        assertThat(gauge("biz.members.interest", "category", "wake_sleep")).isEqualTo(25);
        assertThat(gauge("biz.members.interest", "category", "reading")).as("집계에 없던 칸은 0").isZero();
        assertThat(gauge("biz.members.consent", "type", "marketing")).isEqualTo(9);
        assertThat(gauge("biz.members.active", "window", "1d")).isEqualTo(7);
        assertThat(gauge("biz.members.active", "window", "7d")).isEqualTo(32);
    }

    @Test
    @DisplayName("지표 수는 고정 31개 — 열거 밖 값이 와도 새 지표가 생기지 않는다(과금 단위가 몰래 늘지 않게)")
    void fixedMeterCount() {
        when(service.snapshot()).thenReturn(profile());
        metrics.refresh();

        assertThat(registry.getMeters()).hasSize(31);
        assertThat(registry.find("biz.members.interest").tag("category", "legacy_category").gauge()).isNull();
        assertThat(registry.find("biz.members.consent").tag("type", "tos").gauge()).isNull();
    }

    @Test
    @DisplayName("집계가 실패해도 예외를 밖으로 내지 않고 지난 값을 둔다")
    void keepsLastValuesOnFailure() {
        when(service.snapshot()).thenReturn(profile()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("biz.members.total").gauge().value()).isEqualTo(32);
    }
}
