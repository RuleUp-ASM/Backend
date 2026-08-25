package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import com.ruleup.ruleup_backend.verification.service.TodayStatusView;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 판정 행의 상태 정책 가드 (인증 정책 §2.1 · 테크스펙 §5-1).
 *
 * <p>구 정책의 <b>잠정 실패 → 방장/관리자 승인</b> 2단계는 폐기됐다. 지금은
 * <ul>
 *   <li>성공은 조건 충족 <b>즉시</b> 확정한다.</li>
 *   <li>실패 조건이 먼저 발견돼도 귀속일 중에는 <b>저장하지 않는다</b> — "실패 예정"은 계산 상태다.</li>
 *   <li>실패는 귀속일 다음 날 00:00 KST 에만 확정된다.</li>
 *   <li>확정된 실패는 이의 기간이 끝나기 전까지 방 피드에 공유되지 않는다.</li>
 * </ul>
 */
class VerificationDailyStatePolicyTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 25);

    private VerificationDaily open() {
        VerificationDaily daily = VerificationDaily.open(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TARGET);
        daily.applyWindow(VerificationDeadlines.finalizeAfter(TARGET),
                VerificationDeadlines.finalizeAfter(TARGET));
        return daily;
    }

    @Nested
    @DisplayName("저장 상태")
    class StoredState {

        @Test
        @DisplayName("잠정 실패 상태는 더 이상 존재하지 않는다")
        void provisionalFailureIsGone() {
            assertThat(VerificationStatus.values())
                    .extracting(Enum::name)
                    .doesNotContain("FAILED_PROVISIONAL")
                    .containsExactlyInAnyOrder(
                            "PENDING", "SUCCESS", "FAILED", "NOT_TARGET", "NOT_REQUIRED");
        }

        @Test
        @DisplayName("실패 예정은 저장 상태가 아니다 — 행은 PENDING 인 채로 사유만 달린다")
        void failExpectedStaysPending() {
            VerificationDaily daily = open();
            daily.recordFailExpected("SCREEN_TIME", "USAGE_EXCEEDED");

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.PENDING);
            assertThat(daily.getFailureReason()).isEqualTo("USAGE_EXCEEDED");
            assertThat(daily.getVerifiedAt()).as("확정이 아니다").isNull();
            assertThat(daily.getVerifiedVia()).isNull();
            assertThat(daily.getShareableAt()).as("확정 전에는 공유 대상이 아니다").isNull();
        }

        @Test
        @DisplayName("실패 예정이 붙었다가 조건을 되찾으면 성공으로 확정된다 — 되돌릴 수 있어야 한다")
        void failExpectedCanBeRecovered() {
            VerificationDaily daily = open();
            daily.recordFailExpected("GPS_PRESENCE", "INSUFFICIENT_DWELL");

            Instant at = Instant.parse("2026-08-25T11:00:00Z");
            daily.recordResult(VerificationStatus.SUCCESS, "GPS_PRESENCE", null, at);

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(daily.getFailureReason()).isNull();
            assertThat(daily.getVerifiedVia()).isEqualTo(VerifiedVia.AUTO);
        }
    }

    @Nested
    @DisplayName("확정")
    class Confirmation {

        @Test
        @DisplayName("성공은 즉시 확정되고 즉시 공유 가능하다")
        void successIsImmediate() {
            VerificationDaily daily = open();
            Instant at = Instant.parse("2026-08-25T01:00:00Z");
            daily.recordResult(VerificationStatus.SUCCESS, "GPS_PRESENCE", null, at);

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(daily.getVerifiedAt()).isEqualTo(at);
            assertThat(daily.getShareableAt()).isEqualTo(at);
        }

        @Test
        @DisplayName("실패 확정은 이의 기한을 열고, 그 기한 전에는 피드에 공유되지 않는다")
        void failureOpensAppealWindowAndStaysUnshared() {
            VerificationDaily daily = open();
            daily.recordFailExpected("SCREEN_TIME", "USAGE_EXCEEDED");

            Instant confirmedAt = VerificationDeadlines.finalizeAfter(TARGET);   // 8/26 00:00 KST
            daily.confirmFailure(confirmedAt, "SCREEN_TIME", "USAGE_EXCEEDED");

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.FAILED);
            assertThat(daily.getVerifiedAt()).isEqualTo(confirmedAt);
            assertThat(daily.getFailureReason()).isEqualTo("USAGE_EXCEEDED");
            assertThat(daily.getAppealClosesAt())
                    .as("확정일의 다음 날 00:00 KST")
                    .isEqualTo(Instant.parse("2026-08-26T15:00:00Z"));
            assertThat(daily.getShareableAt())
                    .as("인용될 수도 있는 실패로 망신 주지 않는다 — 이의 기간 이후에만 공유")
                    .isEqualTo(daily.getAppealClosesAt());
        }

        @Test
        @DisplayName("이의가 인용되면 완료로 정정되고 실패 공유 대상에서 빠진다")
        void appealAcceptedCorrectsToSuccess() {
            VerificationDaily daily = open();
            Instant confirmedAt = VerificationDeadlines.finalizeAfter(TARGET);
            daily.confirmFailure(confirmedAt, "GPS_PRESENCE", "INSUFFICIENT_DWELL");

            Instant acceptedAt = confirmedAt.plusSeconds(3600);
            daily.correctByAppeal(acceptedAt);

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(daily.getFailureReason()).isNull();
            assertThat(daily.getVerifiedVia()).isEqualTo(VerifiedVia.APPEAL);
            assertThat(daily.getVerifiedAt()).isEqualTo(acceptedAt);
            assertThat(daily.getShareableAt()).isEqualTo(acceptedAt);
            assertThat(daily.getAppealClosesAt()).as("정정됐으므로 이의 대상이 아니다").isNull();
        }
    }

    @Nested
    @DisplayName("표시 상태 계산")
    class DisplayState {

        private final Instant duringTargetDate = Instant.parse("2026-08-25T05:00:00Z");   // 8/25 14:00 KST
        private final Instant afterMidnight = Instant.parse("2026-08-25T15:30:00Z");      // 8/26 00:30 KST

        @Test
        @DisplayName("귀속일 중 신호가 없으면 진행중")
        void inProgressDuringTargetDate() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null, duringTargetDate))
                    .isEqualTo(TodayStatusView.IN_PROGRESS);
        }

        @Test
        @DisplayName("귀속일 중 위반이 확인되면 실패 예정 — 실패가 아니다")
        void failExpectedDuringTargetDate() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, "USAGE_EXCEEDED", duringTargetDate))
                    .isEqualTo(TodayStatusView.FAIL_EXPECTED);
        }

        @Test
        @DisplayName("귀속일이 끝나고 아직 확정 전이면 검사중 — 실패 예정이었어도 검사중이 우선한다")
        void checkingAfterTargetDateEnds() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null, afterMidnight))
                    .isEqualTo(TodayStatusView.CHECKING);
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, "USAGE_EXCEEDED", afterMidnight))
                    .isEqualTo(TodayStatusView.CHECKING);
        }

        @Test
        @DisplayName("확정된 결과는 완료·실패로 그대로 보인다")
        void terminalStatesMapDirectly() {
            assertThat(TodayStatusView.of(VerificationStatus.SUCCESS, TARGET, null, afterMidnight))
                    .isEqualTo(TodayStatusView.DONE);
            assertThat(TodayStatusView.of(VerificationStatus.FAILED, TARGET, "USAGE_EXCEEDED", afterMidnight))
                    .isEqualTo(TodayStatusView.FAILED);
            assertThat(TodayStatusView.of(VerificationStatus.NOT_TARGET, TARGET, null, duringTargetDate))
                    .isEqualTo(TodayStatusView.NOT_TARGET);
        }
    }
}
