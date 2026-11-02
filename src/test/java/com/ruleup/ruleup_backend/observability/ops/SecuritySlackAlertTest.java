package com.ruleup.ruleup_backend.observability.ops;

import com.ruleup.ruleup_backend.observability.ops.SecuritySlackAlert.RefreshReuse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SecuritySlackAlertTest {

    private static final String USER = "01a19b39-de45-7e89-9be3-226a89e920c3";

    @Test
    @DisplayName("탈취 의심은 제목부터 가른다 — 나머지는 확인 필요")
    void titleBySeverity() {
        assertThat(SecuritySlackAlert.title("prod", RefreshReuse.SUCCESSOR_ROTATED))
                .isEqualTo(":rotating_light: [prod] refresh 토큰 재사용 — 탈취 의심");
        assertThat(SecuritySlackAlert.title("stg", RefreshReuse.GRACE_EXPIRED))
                .isEqualTo(":warning: [stg] refresh 토큰 재사용 — 확인 필요");
    }

    @Test
    @DisplayName("본문은 이유·폐기 뒤 경과·조치·KST 시각·사용자 ID·런북")
    void descriptionCarriesContext() {
        String d = SecuritySlackAlert.description(RefreshReuse.SESSION_ENDED, Duration.ofMinutes(192),
                Instant.parse("2026-11-02T08:20:12Z"), USER);

        assertThat(d.split("\n")).containsExactly(
                "로그아웃·다른 기기 로그인·정지 등으로 이미 끝난 세션의 토큰이 왔다",
                "옛 토큰 폐기 뒤 3시간 12분 · 세션 전체를 끊었다(재로그인 필요)",
                "11-02 17:20 KST · `" + USER + "`",
                "런북 RB-05: https://app.notion.com/p/3f5ad4145fb6811da317e294cf339757");
    }

    @Test
    @DisplayName("경과 시간은 읽기 쉬운 단위로")
    void humanDuration() {
        assertThat(SecuritySlackAlert.human(Duration.ofSeconds(42))).isEqualTo("42초");
        assertThat(SecuritySlackAlert.human(Duration.ofSeconds(302))).isEqualTo("5분");
        assertThat(SecuritySlackAlert.human(Duration.ofSeconds(40895))).isEqualTo("11시간 21분");
        assertThat(SecuritySlackAlert.human(Duration.ofHours(50))).isEqualTo("2일 2시간");
    }

    @Test
    @DisplayName("한 시간에 10건까지만 보낸다 — 반복 공격이 채널을 덮지 못한다")
    void capsPerWindow() {
        SecuritySlackAlert alert = new SecuritySlackAlert(new OpsSlackChannel(""));
        Instant t = Instant.parse("2026-11-02T00:00:00Z");

        for (int i = 0; i < SecuritySlackAlert.MAX_PER_WINDOW; i++) assertThat(alert.admit(t.plusSeconds(i))).isTrue();
        assertThat(alert.admit(t.plusSeconds(60))).isFalse();
        assertThat(alert.admit(t.plus(SecuritySlackAlert.WINDOW).plusSeconds(1))).as("창이 지나면 다시").isTrue();
    }

    @Test
    @DisplayName("토픽이 비면 아무것도 하지 않는다")
    void disabledWithoutTopic() {
        SecuritySlackAlert alert = new SecuritySlackAlert(new OpsSlackChannel(" "));

        assertThatCode(() -> alert.refreshReuse(USER, RefreshReuse.SUCCESSOR_ROTATED, Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
    }
}
