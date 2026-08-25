package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.Polarity;
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
 *   <li>실패 조건이 먼저 발견돼도 <b>저장하지 않는다</b> — "실패 예정"은 계산 상태다.</li>
 *   <li>실패는 귀속일 이틀 뒤 00:00 KST 에만 확정된다. 귀속일 종료 후 하루는 늦은 신호를 받는 유예 구간이다.</li>
 *   <li>이의는 확정 <b>전에</b> 받는다 — 기한이 확정 시각과 같아서, 유예 하루가 실제 신청 창이다.</li>
 * </ul>
 */
class VerificationDailyStatePolicyTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 25);

    /** 8/25 귀속 → 귀속일 종료 8/26 00:00, 확정·이의 마감 8/27 00:00 (KST). */
    private static final Instant DURING_TARGET_DATE = Instant.parse("2026-08-25T05:00:00Z");   // 8/25 14:00
    private static final Instant DURING_GRACE = Instant.parse("2026-08-26T05:00:00Z");         // 8/26 14:00
    private static final Instant AFTER_FINALIZE = Instant.parse("2026-08-26T15:30:00Z");       // 8/27 00:30

    private VerificationDaily open() {
        VerificationDaily daily = VerificationDaily.open(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TARGET);
        daily.applyWindow(null);
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
        @DisplayName("행을 여는 순간 확정·이의 마감이 함께 선다 — 평가 결과에 따라 흔들리면 안 된다")
        void boundariesAreSetWhenTheRowOpens() {
            VerificationDaily daily = open();
            assertThat(daily.getFinalizeAfter()).isEqualTo(VerificationDeadlines.finalizeAfter(TARGET));
            assertThat(daily.getAppealClosesAt())
                    .as("이의 기한은 확정 시각과 같다")
                    .isEqualTo(daily.getFinalizeAfter());
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
        @DisplayName("실패 확정 시점에는 이의 기한이 이미 닫혀 있고, 그때부터 피드에 공유된다")
        void failureIsConfirmedAfterTheAppealWindowClosed() {
            VerificationDaily daily = open();
            daily.recordFailExpected("SCREEN_TIME", "USAGE_EXCEEDED");

            Instant confirmedAt = VerificationDeadlines.finalizeAfter(TARGET);   // 8/27 00:00 KST
            daily.confirmFailure(confirmedAt, "SCREEN_TIME", "USAGE_EXCEEDED");

            assertThat(daily.getStatus()).isEqualTo(VerificationStatus.FAILED);
            assertThat(daily.getVerifiedAt()).isEqualTo(confirmedAt);
            assertThat(daily.getFailureReason()).isEqualTo("USAGE_EXCEEDED");
            assertThat(daily.getShareableAt())
                    .as("이의는 확정 전에 이미 마감됐다 — 여기까지 온 실패는 바로 공유해도 된다")
                    .isEqualTo(confirmedAt);
            assertThat(daily.isAppealable(Polarity.CONSTRAINT, confirmedAt))
                    .as("확정 시각 = 이의 마감").isFalse();
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

        @Test
        @DisplayName("귀속일 중 신호가 없으면 진행중")
        void inProgressDuringTargetDate() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null,
                    Polarity.ACHIEVEMENT, DURING_TARGET_DATE))
                    .isEqualTo(TodayStatusView.IN_PROGRESS);
        }

        @Test
        @DisplayName("귀속일 중이라도 되돌릴 수 없는 위반이 잡히면 실패 예정")
        void failExpectedWhenBreachIsFound() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, "USAGE_EXCEEDED",
                    Polarity.CONSTRAINT, DURING_TARGET_DATE))
                    .isEqualTo(TodayStatusView.FAIL_EXPECTED);
        }

        @Test
        @DisplayName("귀속일이 끝났는데 목표 달성형이 미달이면 실패 예정 — 이 구간이 이의 신청 창이다")
        void unmetAchievementBecomesFailExpectedAfterTargetDate() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null,
                    Polarity.ACHIEVEMENT, DURING_GRACE))
                    .isEqualTo(TodayStatusView.FAIL_EXPECTED);
        }

        @Test
        @DisplayName("규칙 지키기형은 귀속일이 끝나도 위반이 없으면 실패 예정이 아니다 — 오히려 성공 쪽이다")
        void cleanConstraintIsNotFailExpected() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null,
                    Polarity.CONSTRAINT, DURING_GRACE))
                    .isEqualTo(TodayStatusView.IN_PROGRESS);
        }

        @Test
        @DisplayName("확정 시각이 지나고 아직 확정 전이면 검사중 — 짧은 재평가 구간이다")
        void checkingAfterFinalizeBoundary() {
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, null,
                    Polarity.ACHIEVEMENT, AFTER_FINALIZE))
                    .isEqualTo(TodayStatusView.CHECKING);
            assertThat(TodayStatusView.of(VerificationStatus.PENDING, TARGET, "USAGE_EXCEEDED",
                    Polarity.CONSTRAINT, AFTER_FINALIZE))
                    .isEqualTo(TodayStatusView.CHECKING);
        }

        @Test
        @DisplayName("확정된 결과는 완료·실패로 그대로 보인다")
        void terminalStatesMapDirectly() {
            assertThat(TodayStatusView.of(VerificationStatus.SUCCESS, TARGET, null,
                    Polarity.ACHIEVEMENT, AFTER_FINALIZE)).isEqualTo(TodayStatusView.DONE);
            assertThat(TodayStatusView.of(VerificationStatus.FAILED, TARGET, "USAGE_EXCEEDED",
                    Polarity.CONSTRAINT, AFTER_FINALIZE)).isEqualTo(TodayStatusView.FAILED);
            assertThat(TodayStatusView.of(VerificationStatus.NOT_TARGET, TARGET, null,
                    Polarity.ACHIEVEMENT, DURING_TARGET_DATE)).isEqualTo(TodayStatusView.NOT_TARGET);
        }
    }

    @Nested
    @DisplayName("이의 신청 자격")
    class AppealEligibility {

        @Test
        @DisplayName("귀속일 중 아직 채울 기회가 있으면 이의 대상이 아니다")
        void notAppealableWhileStillAchievable() {
            assertThat(open().isAppealable(Polarity.ACHIEVEMENT, DURING_TARGET_DATE)).isFalse();
        }

        @Test
        @DisplayName("위반이 잡히면 귀속일 중에도 이의를 낼 수 있다")
        void appealableOnceBreachIsFound() {
            VerificationDaily daily = open();
            daily.recordFailExpected("SCREEN_TIME", "USAGE_EXCEEDED");
            assertThat(daily.isAppealable(Polarity.CONSTRAINT, DURING_TARGET_DATE)).isTrue();
        }

        @Test
        @DisplayName("목표 미달인 채 귀속일이 끝나면 이의를 낼 수 있다 — 실패의 대다수가 이 경우다")
        void appealableWhenAchievementGoalIsUnmet() {
            assertThat(open().isAppealable(Polarity.ACHIEVEMENT, DURING_GRACE))
                    .as("장소에 안 갔다·걸음 부족도 구제 경로가 있어야 한다")
                    .isTrue();
        }

        @Test
        @DisplayName("확정 시각이 지나면 더는 신청할 수 없다 — 기한이 확정 시각과 같다")
        void notAppealableAfterFinalizeBoundary() {
            VerificationDaily daily = open();
            daily.recordFailExpected("SCREEN_TIME", "USAGE_EXCEEDED");
            assertThat(daily.isAppealable(Polarity.CONSTRAINT, AFTER_FINALIZE)).isFalse();
        }

        @Test
        @DisplayName("이미 완료된 인증은 이의 대상이 아니다")
        void completedVerificationIsNotAppealable() {
            VerificationDaily daily = open();
            daily.recordResult(VerificationStatus.SUCCESS, "GPS_PRESENCE", null, DURING_TARGET_DATE);
            assertThat(daily.isAppealable(Polarity.ACHIEVEMENT, DURING_GRACE)).isFalse();
        }
    }
}
