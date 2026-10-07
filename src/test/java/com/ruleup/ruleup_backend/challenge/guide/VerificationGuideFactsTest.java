package com.ruleup.ruleup_backend.challenge.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 안내 문구의 값 표기와 LLM 응답 검증 — 판정 기준과 다른 숫자가 사용자에게 나가지 않게. */
class VerificationGuideFactsTest {

    @Test
    @DisplayName("값은 사람이 읽는 표기로 — 걸음은 천 단위 쉼표, 분은 시간으로, 시각은 오전·오후")
    void humanReadableValues() {
        assertThat(VerificationGuideFacts.steps("10000")).isEqualTo("10,000걸음");
        assertThat(VerificationGuideFacts.minutes(120)).isEqualTo("2시간");
        assertThat(VerificationGuideFacts.minutes("90")).isEqualTo("1시간 30분");
        assertThat(VerificationGuideFacts.minutes(50)).isEqualTo("50분");
        assertThat(VerificationGuideFacts.hours("7.5")).isEqualTo("7시간 30분");
        assertThat(VerificationGuideFacts.km("5.0")).isEqualTo("5km");
        assertThat(VerificationGuideFacts.clock("07:00")).isEqualTo("오전 7시");
        assertThat(VerificationGuideFacts.clock("06:30")).isEqualTo("오전 6시 30분");
        assertThat(VerificationGuideFacts.clock("23:00")).isEqualTo("밤 11시");
        assertThat(VerificationGuideFacts.clock("23:59")).isEqualTo("자정");
        assertThat(VerificationGuideFacts.clock("01:00")).isEqualTo("새벽 1시");
        assertThat(VerificationGuideFacts.frequency(7)).isEqualTo("매일");
        assertThat(VerificationGuideFacts.frequency(3)).isEqualTo("주 3회");
    }

    private static VerificationGuideFacts steps(String freq) {
        return new VerificationGuideFacts(true, "HEALTH", "하루 만 보 걷기", "만보", freq,
                List.of("하루 10,000걸음 이상 걷기"), List.of("10,000걸음"),
                freq + " 10,000걸음 이상 걸으면 자동 인증됩니다.", "fp");
    }

    @Test
    @DisplayName("LLM 응답은 확정 값·빈도·끝맺음을 지켜야 받는다")
    void acceptance() {
        assertThat(VerificationGuideService.acceptable(steps("매일"), "매일 10,000걸음 걸으면 자동 인증됩니다.")).isTrue();
        // 숫자가 바뀌었다
        assertThat(VerificationGuideService.acceptable(steps("매일"), "매일 1만 보 걸으면 자동 인증됩니다.")).isFalse();
        // 주 N회 가 빠지면 조건이 바뀐다
        assertThat(VerificationGuideService.acceptable(steps("주 3회"), "10,000걸음 걸으면 자동 인증됩니다.")).isFalse();
        // 자동 방인데 수동 안내
        assertThat(VerificationGuideService.acceptable(steps("매일"), "매일 10,000걸음 걷고 직접 인증해 주세요.")).isFalse();
        assertThat(VerificationGuideService.acceptable(steps("매일"), null)).isFalse();
        assertThat(VerificationGuideService.acceptable(steps("매일"), "매일 10,000걸음\n자동 인증됩니다.")).isFalse();
    }

    @Test
    @DisplayName("수동 방은 「직접 인증」 안내여야 한다")
    void manualMustSayDirect() {
        VerificationGuideFacts manual = new VerificationGuideFacts(false, "SELF_CHECK", null, "코딩", "매일",
                List.of(), List.of(), "매일 루틴을 마친 뒤 그날 안에 앱에서 직접 인증해 주세요.", "fp");
        assertThat(VerificationGuideService.acceptable(manual, "매일 코딩 문제를 푼 뒤 그날 안에 앱에서 직접 인증해 주세요.")).isTrue();
        assertThat(VerificationGuideService.acceptable(manual, "매일 코딩 문제를 풀면 자동 인증됩니다.")).isFalse();
    }
}
