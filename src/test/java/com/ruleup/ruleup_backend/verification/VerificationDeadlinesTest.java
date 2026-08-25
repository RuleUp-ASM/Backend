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
 * <p>두 시각만 지키면 나머지 정책이 따라온다.
 * <ul>
 *   <li><b>최종 확정</b> — 귀속일 다음 날 00:00 KST. <b>판정 유형과 무관하게 같다.</b>
 *       유형별 cutoff(수면 12시간·기상 2시간 등)를 각자 두면 사용자·유형별로 확정 시각이 흔들린다.</li>
 *   <li><b>이의 신청 기한</b> — 실패 <b>확정일</b>의 다음 날 00:00 KST. 확정 시각 기준 상대 24시간이 아니라
 *       자정 경계로 고정한다 — 점수·랭킹·통계 재계산을 하루 단위로 묶어 돌리기 위함이다.</li>
 * </ul>
 */
class VerificationDeadlinesTest {

    @Test
    @DisplayName("귀속일의 최종 확정 시각은 다음 날 00:00 KST 다")
    void finalizeAtMidnightAfterTargetDate() {
        assertThat(VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 8, 25)))
                .isEqualTo(Instant.parse("2026-08-25T15:00:00Z"));   // 2026-08-26 00:00 KST
    }

    @Test
    @DisplayName("월말·연말 경계에서도 다음 날 00:00 KST 로 넘어간다")
    void finalizeCrossesMonthAndYearBoundary() {
        assertThat(VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 8, 31)))
                .isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));   // 9/1 00:00 KST
        assertThat(VerificationDeadlines.finalizeAfter(LocalDate.of(2026, 12, 31)))
                .isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));   // 2027-01-01 00:00 KST
    }

    @Test
    @DisplayName("이의 기한은 확정 시각 +24시간이 아니라 확정일 다음 날 00:00 KST 다")
    void appealWindowIsAMidnightBoundaryNotRelative24Hours() {
        // 확정이 2026-08-26 00:00 KST 에 났다 → 확정일은 8/26 → 기한은 8/27 00:00 KST.
        Instant confirmedAt = Instant.parse("2026-08-25T15:00:00Z");
        assertThat(VerificationDeadlines.appealClosesAt(confirmedAt))
                .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));
    }

    @Test
    @DisplayName("같은 확정일 안이면 확정 시각이 달라도 이의 기한은 같다")
    void sameConfirmationDateSharesTheSameDeadline() {
        Instant justAfterMidnight = Instant.parse("2026-08-25T15:00:01Z");   // 8/26 00:00:01 KST
        Instant lateEvening = Instant.parse("2026-08-26T13:59:00Z");         // 8/26 22:59 KST
        assertThat(VerificationDeadlines.appealClosesAt(justAfterMidnight))
                .isEqualTo(VerificationDeadlines.appealClosesAt(lateEvening))
                .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));
    }

    @Test
    @DisplayName("정상 흐름에서 이의 기한은 귀속일 기준 D+2 00:00 KST 로 떨어진다")
    void appealDeadlineIsTwoDaysAfterTargetDate() {
        LocalDate targetDate = LocalDate.of(2026, 8, 25);
        Instant confirmedAt = VerificationDeadlines.finalizeAfter(targetDate);   // D+1 00:00 KST
        assertThat(VerificationDeadlines.appealClosesAt(confirmedAt))
                .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));              // D+2 00:00 KST
    }
}
