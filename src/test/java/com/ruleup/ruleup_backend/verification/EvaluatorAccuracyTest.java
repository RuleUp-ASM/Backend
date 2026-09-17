package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 평가기가 문서에 적힌 판정 기준을 실제로 지키는지 (인증 정책 §1.1 · 테크 스펙 §5-1 판정 유형).
 *
 * <p>여기 모인 네 가지는 전부 "문서에는 있는데 코드에 없던" 게이트다. 없으면 조용히 틀린 판정이 나간다 —
 * 스쳐 지나간 편의점이 위반이 되고, 조작된 위치가 인증을 통과하고, 알림 확인이 기상으로 잡힌다.
 */
class EvaluatorAccuracyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET = LocalDate.of(2026, 8, 25);
    private static final String MEMBER = "11111111-2222-3333-4444-555555555555";

    /** 게이트 강화를 끈 기본 설정. 나머지 값은 record 가 기본값으로 채운다. */
    private static com.ruleup.ruleup_backend.verification.config.VerificationProperties lenientProperties() {
        return new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                null, null, null, null, null, null, null, null, null, null, false, false);
    }

    /** 출처 없는 기록을 거부하는 설정. */
    private static com.ruleup.ruleup_backend.verification.config.VerificationProperties strictOriginProperties() {
        return new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                null, null, null, null, null, null, null, null, null, null, false, true);
    }

    private static Instant at(int hour, int minute) {
        return TARGET.atTime(hour, minute).atZone(KST).toInstant();
    }

    private DayContext ctx(VerificationConfig config, List<SyncSignal> signals,
                           List<GeoAnchor> anchors, Instant now) {
        return new DayContext(TARGET, KST, now, config, signals, anchors, List.of(), MEMBER);
    }

    // ===== 설정 조립 =====

    private VerificationConfig gpsConfig(GpsPresence presence, int dwellMinutes, int graceMinutes,
                                         int accuracyMaxM) {
        GpsConfig gps = new GpsConfig(
                GpsMode.PRESENCE, presence, AnchorFillMode.MANUAL, null, 1500,
                null, null, 200, dwellMinutes, graceMinutes, null, null,
                presence == GpsPresence.AVOID ? Polarity.CONSTRAINT : Polarity.ACHIEVEMENT,
                1, accuracyMaxM, 50, List.of());
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.GPS_PRESENCE), gps, null, null, null, null, List.of());
    }

    private VerificationConfig wakeConfig(String beforeTime) {
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.WAKE), null, null, null,
                new WakeConfig(beforeTime, Polarity.ACHIEVEMENT, 1), null, List.of());
    }

    private VerificationConfig sleepConfig(BigDecimal minHours) {
        return sleepConfig(minHours, List.of());
    }

    private VerificationConfig sleepConfig(BigDecimal minHours, List<String> trustedOrigins) {
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.SLEEP), null, null, null, null,
                new SleepConfig(null, minHours, Polarity.ACHIEVEMENT, 12, trustedOrigins), List.of());
    }

    private static SyncSignal geofence(String transition, Instant when, Boolean isMock) {
        return new SyncSignal("GEOFENCE", null, when.toString(),
                List.of(new GeofenceTransition(MEMBER, transition, when.toString(), isMock)),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SyncSignal location(double lat, double lng, Double accuracy, Boolean isMock,
                                       List<Instant> times) {
        List<GeoPoint> points = times.stream()
                .map(t -> new GeoPoint(lat, lng, accuracy, t.toString(), isMock)).toList();
        return new SyncSignal("LOCATION", null, times.get(times.size() - 1).toString(),
                null, points, isMock, null, null, null, null, null, null, null, null, null);
    }

    private static SyncSignal screen(String event, Instant when) {
        return new SyncSignal("SCREEN_TIME", null, when.toString(), null, null, null, null,
                null, null, null, TARGET.toString(), null,
                List.of(new ScreenEvent(event, when.toString())), null, null);
    }

    private static SyncSignal sleep(Instant start, Instant end, HealthOrigin origin) {
        return new SyncSignal("SLEEP", null, end.toString(), null, null, null, null,
                null, null, null, null, null, null,
                List.of(new SleepSegment(start.toString(), end.toString(), "ASLEEP", origin)), null);
    }

    @Nested
    @DisplayName("장소 피하기 — 스침 유예")
    class AvoidGrace {

        private final GpsPresenceEvaluator evaluator = new GpsPresenceEvaluator();

        @Test
        @DisplayName("허용 시간 안에 나오면 스침으로 보고 위반이 아니다")
        void briefPassThroughIsNotAViolation() {
            // 5분 유예. 2분 머물다 나왔다 — 편의점 앞을 지나친 정도다.
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("ENTER", at(19, 0), false), geofence("EXIT", at(19, 2), false)),
                    List.of(), at(20, 0)));

            assertThat(outcome.failureReason()).as("스침은 위반이 아니다").isNull();
            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        @DisplayName("허용 시간을 넘겨 머물면 위반이다")
        void stayingBeyondGraceIsAViolation() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("ENTER", at(19, 0), false), geofence("EXIT", at(19, 30), false)),
                    List.of(), at(20, 0)));

            assertThat(outcome.failureReason()).isEqualTo("ENTERED_AVOID_ZONE");
        }

        @Test
        @DisplayName("아직 안 나왔고 허용 시간도 안 지났으면 판단을 미룬다 — 이탈 신호가 늦게 올 수 있다")
        void openStayWithinGraceWaits() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("ENTER", at(19, 0), false)),
                    List.of(), at(19, 3)));

            assertThat(outcome.failureReason()).isNull();
        }

        @Test
        @DisplayName("안 나온 채 허용 시간이 지나면 위반이다")
        void openStayBeyondGraceIsAViolation() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("ENTER", at(19, 0), false)),
                    List.of(), at(19, 30)));

            assertThat(outcome.failureReason()).isEqualTo("ENTERED_AVOID_ZONE");
        }

        @Test
        @DisplayName("OS 가 체류를 확정한 DWELL 은 유예 없이 위반이다")
        void dwellIsImmediateViolation() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("DWELL", at(19, 0), false)),
                    List.of(), at(19, 1)));

            assertThat(outcome.failureReason()).isEqualTo("ENTERED_AVOID_ZONE");
        }

        @Test
        @DisplayName("조작된 위치 신호는 위반 근거가 되지 않는다")
        void mockLocationIsNotEvidence() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.AVOID, 0, 5, 100),
                    List.of(geofence("DWELL", at(19, 0), true)),
                    List.of(), at(20, 0)));

            assertThat(outcome.failureReason()).isNull();
        }
    }

    @Nested
    @DisplayName("장소 방문 — 측위 품질 게이트")
    class VisitQualityGate {

        private final GpsPresenceEvaluator evaluator = new GpsPresenceEvaluator();
        private final List<GeoAnchor> anchors = List.of(new GeoAnchor(37.4979, 127.0276, 200, "헬스장"));

        private List<Instant> fortyMinutes() {
            return List.of(at(9, 0), at(9, 10), at(9, 20), at(9, 30), at(9, 40));
        }

        @Test
        @DisplayName("정확도가 좋은 측위는 체류로 인정된다")
        void accuratePointsCount() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.VISIT, 30, 30, 100),
                    List.of(location(37.4979, 127.0276, 10.0, false, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("정확도가 허용 기준보다 나쁜 측위는 체류 계산에서 빠진다")
        void inaccuratePointsAreExcluded() {
            // accuracy 500m > 허용 100m — 이 좌표가 정말 반경 안인지 알 수 없다.
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.VISIT, 30, 30, 100),
                    List.of(location(37.4979, 127.0276, 500.0, false, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        @DisplayName("조작된 측위는 체류 계산에서 빠진다")
        void mockPointsAreExcluded() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(GpsPresence.VISIT, 30, 30, 100),
                    List.of(location(37.4979, 127.0276, 10.0, true, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("기상 — 잠금 해제만 인정")
    class WakeUnlockOnly {

        private final WakeEvaluator evaluator = new WakeEvaluator();

        @Test
        @DisplayName("잠금 해제는 기상으로 인정된다")
        void unlockCounts() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(screen("UNLOCK", at(6, 30))), List.of(), at(8, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("화면만 켜진 것은 기상이 아니다 — 알림 확인으로도 켜진다")
        void screenOnAloneIsNotWakingUp() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(screen("SCREEN_ON", at(6, 30))), List.of(), at(8, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("수면 — 신뢰 출처와 세그먼트 누적")
    class SleepTrustAndAccumulation {

        /** 기본값(관대 모드) — 출처 누락은 통과. 엄격 모드는 아래에서 따로 확인한다. */
        private final SleepEvaluator evaluator = new SleepEvaluator(lenientProperties());
        private static final HealthOrigin TRUSTED =
                new HealthOrigin("com.sec.android.app.shealth", "AUTO", "WATCH");
        private static final HealthOrigin HAND_WRITTEN =
                new HealthOrigin("com.sec.android.app.shealth", "MANUAL", "PHONE");

        @Test
        @DisplayName("손으로 입력한 수면 기록은 판정에 쓰지 않는다")
        void manuallyEnteredSleepIsExcluded() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")),
                    List.of(sleep(at(23, 0), at(23, 0).plusSeconds(8 * 3600), HAND_WRITTEN)),
                    List.of(), at(23, 30).plusSeconds(9 * 3600)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("[P1] 화이트리스트 밖 출처는 자동 기록이어도 판정에 쓰지 않는다")
        void untrustedOriginIsExcludedEvenWhenAutomaticallyRecorded() {
            Instant now = at(23, 0).plusSeconds(10 * 3600);
            // 「AUTO」만 붙이면 아무 앱이나 수면을 써 넣을 수 있다. MANUAL 만 걸러서는
            // 스펙의 「신뢰 가능한 Health Connect 수면 기록만 사용」이 지켜지지 않는다.
            HealthOrigin fake = new HealthOrigin("com.example.fake", "AUTO", "PHONE");
            SyncSignal eightHours = sleep(at(23, 0), at(23, 0).plusSeconds(8 * 3600), fake);
            List<String> allow = List.of("com.sec.android.app.shealth");

            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7"), allow), List.of(eightHours), List.of(), now)).status())
                    .as("목록 밖 출처가 통과하면 수면 인증은 사실상 자기 신고가 된다")
                    .isNotEqualTo(VerificationStatus.SUCCESS);

            // 같은 기록이라도 신뢰 출처면 그대로 인정된다 — 게이트가 과하게 닫히지 않는지도 본다.
            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7"), allow),
                    List.of(sleep(at(23, 0), at(23, 0).plusSeconds(8 * 3600), TRUSTED)),
                    List.of(), now)).status())
                    .isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("여러 sync 에 나뉘어 온 수면 구간이 합산된다")
        void segmentsAcrossSyncsAccumulate() {
            Instant now = at(23, 0).plusSeconds(10 * 3600);
            SyncSignal early = sleep(at(23, 0), at(23, 0).plusSeconds(4 * 3600), TRUSTED);
            SyncSignal late = sleep(at(23, 0).plusSeconds(4 * 3600), at(23, 0).plusSeconds(8 * 3600), TRUSTED);

            // 4시간만 있을 때는 목표 7시간에 못 미친다.
            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")), List.of(early), List.of(), now)).status())
                    .isNotEqualTo(VerificationStatus.SUCCESS);

            // 나머지 4시간이 뒤늦게 도착해 원본에 쌓이면 합산된다 — 앞 구간을 잊으면 영영 못 채운다.
            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")), List.of(early, late), List.of(), now)).status())
                    .isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("출처를 밝히지 않은 수면 기록은 엄격 모드에서 판정에서 빠진다")
        void segmentsWithoutOriginAreExcludedInStrictMode() {
            SleepEvaluator strict = new SleepEvaluator(strictOriginProperties());
            Instant now = at(23, 0).plusSeconds(10 * 3600);
            // 출처가 없는 8시간 수면 — 관대 모드면 통과, 엄격 모드면 근거로 쓸 수 없다.
            SyncSignal noOrigin = sleep(at(23, 0), at(23, 0).plusSeconds(8 * 3600), null);

            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")), List.of(noOrigin), List.of(), now)).status())
                    .as("지금 바로 조이면 출처를 안 보내는 클라의 수면 인증이 전부 막힌다")
                    .isEqualTo(VerificationStatus.SUCCESS);

            assertThat(strict.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")), List.of(noOrigin), List.of(), now)).status())
                    .as("걸음·거리는 출처가 없으면 거부하는데 수면만 통과시키면 일관되지 않다")
                    .isNotEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("같은 구간이 두 번 남아 있어도 두 번 세지 않는다")
        void resentSegmentIsNotDoubleCounted() {
            Instant now = at(23, 0).plusSeconds(10 * 3600);
            SyncSignal segment = sleep(at(23, 0), at(23, 0).plusSeconds(4 * 3600), TRUSTED);

            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7")), List.of(segment, segment), List.of(), now));

            assertThat(outcome.status())
                    .as("4시간을 두 번 세면 8시간이 되어 목표를 넘겨버린다")
                    .isNotEqualTo(VerificationStatus.SUCCESS);
        }
    }
}
