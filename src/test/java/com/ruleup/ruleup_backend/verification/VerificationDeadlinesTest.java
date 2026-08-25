package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정·이의 경계 정책 가드 (인증 정책 §2 · 인증 구현 테크스펙 §5-1).
 *
 * <pre>
 *   25일           26일           27일
 *   │── 귀속일 ───►│── 유예 ─────►│
 *                  │              │
 *             수행 종료      최종 확정 · 이의 마감
 * </pre>
 * <ul>
 *   <li><b>귀속일 종료 D+1 00:00</b> — 더 채울 기회가 없다. 아직 확정하지는 않는다.</li>
 *   <li><b>최종 확정 D+2 00:00</b> — 판정 유형과 무관하게 같다. 유형별 cutoff 를 각자 두면
 *       확정 시각이 사용자마다 달라진다.</li>
 *   <li><b>이의 기한 = 확정 시각</b> — 확정 시각 기준 상대 24시간이 아니라 자정 경계로 고정한다.
 *       이의는 확정 전에 받으므로 유예 하루가 실제 신청 창이다.</li>
 * </ul>
 * 유예 하루를 두는 이유는 신호가 늦게 도착하기 때문이다. 절전·오프라인·Health Connect 수면 세션처럼
 * 귀속일이 끝난 뒤에야 올라오는 기록이 흔해서, 귀속일 종료 즉시 확정하면 실제로 수행한 사람이 실패한다.
 */
class VerificationDeadlinesTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 25);

    @Test
    @DisplayName("귀속일은 다음 날 00:00 KST 에 끝난다 — 여기서는 아직 확정하지 않는다")
    void targetDateEndsAtMidnightAfterTheDay() {
        assertThat(VerificationDeadlines.targetDateEndsAt(TARGET))
                .isEqualTo(Instant.parse("2026-08-25T15:00:00Z"));   // 8/26 00:00 KST
    }

    @Test
    @DisplayName("최종 확정은 귀속일 이틀 뒤 00:00 KST 다 — 귀속일 종료 후 하루를 더 기다린다")
    void finalizeTwoDaysAfterTargetDate() {
        assertThat(VerificationDeadlines.finalizeAfter(TARGET))
                .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));   // 8/27 00:00 KST
    }

    @Test
    @DisplayName("이의 기한은 확정 시각과 같다 — 확정 전에 받는다")
    void appealDeadlineEqualsFinalizeBoundary() {
        assertThat(VerificationDeadlines.appealClosesAt(TARGET))
                .isEqualTo(VerificationDeadlines.finalizeAfter(TARGET))
                .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));
    }

    @Test
    @DisplayName("월말·연말 경계에서도 같은 규칙으로 넘어간다")
    void boundariesCrossMonthAndYear() {
        assertThat(VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 8, 30)))
                .isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));   // 9/1 00:00 KST
        assertThat(VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 12, 30)))
                .isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));   // 2027-01-01 00:00 KST
    }

    @Test
    @DisplayName("귀속일 종료와 확정은 서로 다른 시각이다 — 그 사이가 늦은 신호를 받는 유예 구간이다")
    void graceWindowSitsBetweenTheTwoBoundaries() {
        Instant duringTargetDate = Instant.parse("2026-08-25T05:00:00Z");   // 8/25 14:00 KST
        Instant duringGrace = Instant.parse("2026-08-26T05:00:00Z");        // 8/26 14:00 KST
        Instant afterFinalize = Instant.parse("2026-08-26T15:30:00Z");      // 8/27 00:30 KST

        assertThat(VerificationDeadlines.targetDateEnded(TARGET, duringTargetDate)).isFalse();
        assertThat(VerificationDeadlines.finalizeDue(TARGET, duringTargetDate)).isFalse();

        assertThat(VerificationDeadlines.targetDateEnded(TARGET, duringGrace))
                .as("귀속일은 끝났지만").isTrue();
        assertThat(VerificationDeadlines.finalizeDue(TARGET, duringGrace))
                .as("아직 확정하지 않는다 — 늦게 도착한 신호를 여기서 받는다").isFalse();

        assertThat(VerificationDeadlines.finalizeDue(TARGET, afterFinalize)).isTrue();
    }

    @Test
    @DisplayName("경계 시각 자체는 이미 지난 것으로 본다 — 00:00 정각에 확정된다")
    void boundaryInstantItselfCounts() {
        assertThat(VerificationDeadlines.finalizeDue(TARGET, VerificationDeadlines.finalizeAfter(TARGET))).isTrue();
        assertThat(VerificationDeadlines.targetDateEnded(TARGET, VerificationDeadlines.targetDateEndsAt(TARGET))).isTrue();
    }
}
