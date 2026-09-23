package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.evaluator.*;
import com.ruleup.ruleup_backend.verification.signal.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수면 합산과 미래 기록 — 둘 다 <b>조용히 더 자게 만들어 주는</b> 경로다.
 *
 * <p>Health Connect 는 한 밤을 여러 조각으로 쪼개 보내고 그 조각이 서로 겹치는 일이 흔하다.
 * 길이를 그냥 더하면 겹친 시간이 두 번 세어지고, 미래 구간을 현재 시각으로만 거르면
 * 아침이 지난 뒤 재평가에서 되살아난다.
 */
class SleepUnionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 귀속 밤 — 이 날 저녁부터 다음 날 새벽까지가 한 밤이다(§2.17). */
    private static final LocalDate NIGHT = LocalDate.of(2026, 9, 17);
    private static final String TRUSTED_ORIGIN = "com.sec.android.app.shealth";
    private static final HealthOrigin TRUSTED = new HealthOrigin(TRUSTED_ORIGIN, "AUTO", "PHONE");

    private final SleepEvaluator evaluator = new SleepEvaluator(
            new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                    null, null, null, null, null, null, null, null, null, null, false, false));

    private static Instant at(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(KST).toInstant();
    }

    /** 아침에 도착한 기록. 미래 구간 차단에 걸리지 않게 수신 시각을 구간 뒤에 둔다. */
    private static SyncSignal sleep(Instant start, Instant end) {
        return sleep(start, end, at(18, 8, 0));
    }

    private static SyncSignal sleep(Instant start, Instant end, Instant receivedAt) {
        return sleep(start, end, receivedAt, TRUSTED);
    }

    private static SyncSignal sleep(Instant start, Instant end, Instant receivedAt, HealthOrigin origin) {
        return new SyncSignal("SLEEP", null, end.toString(), null, null, null, null,
                null, null, null, null, null, null,
                List.of(new SleepSegment(start.toString(), end.toString(), "ASLEEP", origin)),
                receivedAt);
    }

    /** 손으로 적어 넣은 기록 — 출처는 같은 앱이지만 판정에는 쓰지 않는다. */
    private static SyncSignal manualSleep(Instant start, Instant end) {
        return sleep(start, end, at(18, 8, 0), new HealthOrigin(TRUSTED_ORIGIN, "MANUAL", "PHONE"));
    }

    private EvaluationOutcome evaluate(BigDecimal goalHours, Instant now, SyncSignal... signals) {
        VerificationConfig config = new VerificationConfig(ScheduleType.FIXED_DAYS, null,
                MethodCombine.AND, List.of(VerificationMethod.SLEEP), null, null, null, null,
                new SleepConfig(null, goalHours, Polarity.ACHIEVEMENT, 12, List.of(TRUSTED_ORIGIN)),
                List.of());
        return evaluator.evaluate(new DayContext(NIGHT, KST, now, config,
                List.of(signals), List.of(), List.of(), "member"));
    }

    @Nested
    @DisplayName("잔 시간은 구간의 합집합이다")
    class Union {

        @Test
        @DisplayName("겹치는 두 조각 — 23~03시와 00~04시는 8시간이 아니라 5시간이다")
        void overlappingSegmentsCountOnce() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 9, 0),
                    sleep(at(17, 23, 0), at(18, 3, 0)),
                    sleep(at(18, 0, 0), at(18, 4, 0)));

            assertThat(outcome.evidence()).containsEntry("sleepHours", 5.0);
            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.failureReason()).isEqualTo("INSUFFICIENT_SLEEP");
        }

        @Test
        @DisplayName("한 조각이 다른 조각에 통째로 들어 있으면 바깥 것만 센다")
        void containedSegmentAddsNothing() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(5), at(18, 9, 0),
                    sleep(at(17, 23, 0), at(18, 5, 0)),
                    sleep(at(18, 0, 0), at(18, 2, 0)));

            assertThat(outcome.evidence()).containsEntry("sleepHours", 6.0);
            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("끊긴 조각은 각각 센다 — 분할 전송 순서가 뒤바뀌어도 같다")
        void disjointSegmentsAddUpRegardlessOfOrder() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(5), at(18, 9, 0),
                    sleep(at(18, 2, 0), at(18, 5, 0)),
                    sleep(at(17, 22, 0), at(18, 1, 0)));

            assertThat(outcome.evidence()).containsEntry("sleepHours", 6.0);
            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("제외한 구간이 정상 구간의 자리를 막지 않는다")
    class ExclusionOrder {

        @Test
        @DisplayName("손입력이 먼저 와 있어도 같은 구간의 자동 기록은 인정된다")
        void manualFirstDoesNotSuppressTheLaterAutoRecord() {
            // 중복 판정을 출처 검증보다 먼저 걸면, 손입력이 「이미 반영했다」로 등록돼
            // 뒤따라 온 정상 기록이 재전송으로 걸러진다 — 그 밤의 인증이 통째로 막힌다.
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 9, 0),
                    manualSleep(at(17, 22, 0), at(18, 6, 0)),
                    sleep(at(17, 22, 0), at(18, 6, 0)));

            assertThat(outcome.evidence()).containsEntry("sleepHours", 8.0);
            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("손입력만 있으면 그대로 미인정이다")
        void manualOnlyStillDoesNotCount() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 9, 0),
                    manualSleep(at(17, 22, 0), at(18, 6, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(outcome.evidence()).containsEntry("untrustedExcluded", true);
        }

        @Test
        @DisplayName("자동 기록이 먼저 와도 결과는 같다 — 도착 순서가 판정을 바꾸지 않는다")
        void autoFirstGivesTheSameAnswer() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 9, 0),
                    sleep(at(17, 22, 0), at(18, 6, 0)),
                    manualSleep(at(17, 22, 0), at(18, 6, 0)));

            assertThat(outcome.evidence()).containsEntry("sleepHours", 8.0);
            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("아직 오지 않은 잠은 시간이 지나도 잔 잠이 되지 않는다")
    class Future {

        @Test
        @DisplayName("받은 때 기준으로 미래였던 구간은 아침에 다시 평가해도 제외된다")
        void futureAtReceiptStaysExcluded() {
            // 09-18 01:05 에 「09-17 22:10 ~ 09-18 06:30」 을 미리 올린다.
            SyncSignal claimedEarly = sleep(at(17, 22, 10), at(18, 6, 30), at(18, 1, 5));

            // 아침 06:31 에 마감 배치가 같은 원본을 다시 읽는다 — 새 기록은 하나도 오지 않았다.
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 6, 31), claimedEarly);

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(outcome.evidence()).containsEntry("excludedFuture", 1);
        }

        /**
         * <b>평가기 계약만</b> 본다 — 신호가 여기까지 오는 과정은 보지 않는다.
         *
         * <p>실제로는 같은 내용이 다시 오면 적재가 중복으로 걸러 낸다. 그래서 「나중 수신 시각」이
         * 생기려면 적재가 기존 행의 수신 시각을 밀어 줘야 하고, 그쪽은
         * {@code VerificationSignalIngestIT.resendAdvancesReceivedAt} 이 지킨다. 둘이 합쳐져야
         * 「미리 올렸다가 자고 난 뒤 다시 보내면 인정된다」가 성립한다.
         */
        @Test
        @DisplayName("나중에 받은 같은 구간은 인정된다 — 적재가 수신 시각을 밀어 준 뒤의 모습이다")
        void sameSegmentIsAcceptedWhenReportedAfterwards() {
            EvaluationOutcome outcome = evaluate(BigDecimal.valueOf(7), at(18, 9, 0),
                    sleep(at(17, 22, 10), at(18, 6, 30), at(18, 1, 5)),      // 미리 올린 것 — 제외
                    sleep(at(17, 22, 10), at(18, 6, 30), at(18, 6, 35)));    // 자고 나서 올라온 것

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("excludedFuture", 1);
        }
    }
}
