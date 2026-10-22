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
 * 스쳐 지나간 편의점이 위반이 되고, 조작된 위치가 인증을 통과하고, 누워서 알람만 끈 것이 기상으로 잡힌다.
 */
class EvaluatorAccuracyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET = LocalDate.of(2026, 8, 25);
    private static final String MEMBER = "11111111-2222-3333-4444-555555555555";

    /** 게이트 강화를 끈 기본 설정. 나머지 값은 record 가 기본값으로 채운다. */
    private static com.ruleup.ruleup_backend.verification.config.VerificationProperties lenientProperties() {
        return new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                null, null, null, null, null, null, null, null, null, false, false, null, null);
    }

    /** 출처 없는 기록을 거부하는 설정. */
    private static com.ruleup.ruleup_backend.verification.config.VerificationProperties strictOriginProperties() {
        return new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                null, null, null, null, null, null, null, null, null, false, true, null, null);
    }

    private static Instant at(int hour, int minute) {
        return TARGET.atTime(hour, minute).atZone(KST).toInstant();
    }

    private DayContext ctx(VerificationConfig config, List<SyncSignal> signals,
                           List<GeoAnchor> anchors, Instant now) {
        return new DayContext(TARGET, KST, now, config, signals, anchors, List.of(), MEMBER);
    }

    // ===== 설정 조립 =====

    private VerificationConfig gpsConfig(int dwellMinutes, int graceMinutes, int accuracyMaxM) {
        GpsConfig gps = new GpsConfig(
                GpsMode.PRESENCE, AnchorFillMode.MANUAL, null, 1500,
                null, null, 200, dwellMinutes, graceMinutes, null, null,
                Polarity.ACHIEVEMENT, 1, accuracyMaxM, 50, List.of());
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.GPS_PRESENCE), gps, null, null, null, null, List.of());
    }

    private VerificationConfig wakeConfig(String beforeTime) {
        return wakeConfig(beforeTime, List.of("com.sec.android.app.shealth"));
    }

    private VerificationConfig wakeConfig(String beforeTime, List<String> trustedOrigins) {
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.WAKE), null, null, null,
                new WakeConfig(beforeTime, 30, 10, trustedOrigins, Polarity.ACHIEVEMENT, 1), null, List.of());
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

    private static final HealthOrigin WATCH = new HealthOrigin("com.sec.android.app.shealth", "AUTO", "WATCH");

    private static SyncSignal steps(double count, Instant start, Instant end, HealthOrigin origin) {
        HealthReading reading = new HealthReading(null, "STEPS", BigDecimal.valueOf(count), "count",
                start.toString(), end.toString(), null, origin);
        return new SyncSignal("HEALTH", null, end.toString(), null, null, null, List.of(reading),
                null, null, null, TARGET.toString(), null, null, null, null);
    }

    private static SyncSignal stepsRecord(String recordId, double count, Instant start, Instant end, Instant receivedAt) {
        HealthReading reading = new HealthReading(recordId, "STEPS", BigDecimal.valueOf(count), "count",
                start.toString(), end.toString(), null, WATCH);
        return new SyncSignal("HEALTH", null, end.toString(), null, null, null, List.of(reading),
                null, null, null, TARGET.toString(), null, null, null, receivedAt);
    }

    private static UsageEvent use(String pkg, String type, Instant when) {
        return new UsageEvent(pkg, type, when.toString());
    }

    private static SyncSignal usage(List<UsageEvent> events, Instant receivedAt) {
        return new SyncSignal("SCREEN_TIME", null, receivedAt.toString(), null, null, null, null,
                null, null, null, TARGET.toString(), events, null, null, receivedAt);
    }

    private VerificationConfig screenTimeConfig(ScreenTimeMode mode, int goalMinutes) {
        return new VerificationConfig(ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.SCREEN_TIME), null, null,
                new ScreenTimeConfig(mode, mode == ScreenTimeMode.MAX ? Polarity.CONSTRAINT : Polarity.ACHIEVEMENT,
                        List.of("com.instagram.android"), goalMinutes, null, 1),
                null, null, List.of());
    }

    private static SyncSignal sleep(Instant start, Instant end, HealthOrigin origin) {
        return new SyncSignal("SLEEP", null, end.toString(), null, null, null, null,
                null, null, null, null, null, null,
                List.of(new SleepSegment(start.toString(), end.toString(), "ASLEEP", origin)), null);
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
                    gpsConfig(30, 30, 100),
                    List.of(location(37.4979, 127.0276, 10.0, false, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("정확도가 허용 기준보다 나쁜 측위는 체류 계산에서 빠진다")
        void inaccuratePointsAreExcluded() {
            // accuracy 500m > 허용 100m — 이 좌표가 정말 반경 안인지 알 수 없다.
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(30, 30, 100),
                    List.of(location(37.4979, 127.0276, 500.0, false, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        @DisplayName("앱 수집 주기(약 30분)로 한 번씩 찍힌 측위도 체류로 이어진다 — QA SIG-24")
        void thirtyMinuteSamplingCounts() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(60, 60, 150),
                    List.of(location(37.4979, 127.0276, 120.0, false, List.of(at(9, 0))),
                            location(37.4979, 127.0276, 90.0, false, List.of(at(9, 32))),
                            location(37.4979, 127.0276, 110.0, false, List.of(at(10, 1)))),
                    anchors, at(10, 5)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("사이에 반경 밖 측위가 끼면 그 구간은 체류가 아니다")
        void leavingBreaksContinuity() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(30, 30, 150),
                    List.of(location(37.4979, 127.0276, 20.0, false, List.of(at(9, 0))),
                            location(37.5200, 127.0600, 20.0, false, List.of(at(9, 15))),
                            location(37.4979, 127.0276, 20.0, false, List.of(at(9, 40)))),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(outcome.evidence().get("dwellSeconds")).isEqualTo(0L);
        }

        @Test
        @DisplayName("45분을 넘는 공백은 잇지 않는다")
        void longGapIsNotBridged() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(30, 30, 150),
                    List.of(location(37.4979, 127.0276, 20.0, false, List.of(at(9, 0), at(9, 50)))),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        @DisplayName("조작된 측위는 체류 계산에서 빠진다")
        void mockPointsAreExcluded() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    gpsConfig(30, 30, 100),
                    List.of(location(37.4979, 127.0276, 10.0, true, fortyMinutes())),
                    anchors, at(10, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("기상 — 목표 시각 + 30분 안의 걸음")
    class WakeBySteps {

        private final WakeEvaluator evaluator = new WakeEvaluator();

        @Test
        @DisplayName("목표 시각 뒤 유예 안에 걸으면 기상으로 인정된다")
        void stepsInsideWindowCount() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(40, at(7, 10), at(7, 15), WATCH)), List.of(), at(7, 20)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("잠금 해제만으로는 기상이 아니다 — 누워서 알람만 꺼도 잡힌다")
        void unlockAloneIsNotWakingUp() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(screen("UNLOCK", at(6, 30))), List.of(), at(8, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.failureReason()).isEqualTo("WOKE_UP_LATE");
        }

        @Test
        @DisplayName("유예가 지난 뒤의 걸음은 늦잠이다")
        void stepsAfterDeadlineAreLate() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(500, at(8, 0), at(8, 10), WATCH)), List.of(), at(9, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.failureReason()).isEqualTo("WOKE_UP_LATE");
            assertThat(outcome.evidence()).containsEntry("value", 0L).containsEntry("deadline", "07:30");
        }

        @Test
        @DisplayName("마감에 걸친 기록은 창 안에 든 비율만큼만 센다")
        void straddlingRecordIsProrated() {
            // 07:20~07:40 의 16걸음 중 마감(07:30) 전은 절반, 8걸음 — 기준 10걸음에 못 미친다.
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(16, at(7, 20), at(7, 40), WATCH)), List.of(), at(9, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("value", 8L);
        }

        @Test
        @DisplayName("손으로 입력한 걸음은 기상 근거가 아니다")
        void manualStepsAreExcluded() {
            HealthOrigin handWritten = new HealthOrigin("com.sec.android.app.shealth", "MANUAL", "PHONE");
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(100, at(7, 0), at(7, 5), handWritten)), List.of(), at(8, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
        }

        @Test
        @DisplayName("같은 기록이 갱신돼 다시 오면 마지막 값으로 센다 — 처음 받은 3걸음에 묶이지 않는다")
        void updatedRecordUsesLatestValue() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(wakeConfig("07:00"), List.of(
                    stepsRecord("hc-1", 3, at(7, 0), at(7, 2), at(7, 3)),
                    stepsRecord("hc-1", 24, at(7, 0), at(7, 8), at(7, 9))), List.of(), at(7, 10)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("value", 24L);
        }

        @Test
        @DisplayName("Health Connect 가 폰에서 직접 센 걸음도 기상 근거다 — 출처 끝의 기기별 해시는 접두사로 인정한다")
        void healthConnectPhoneStepsCount() {
            // 2026-10-22 prod 실측: 07:02 의 16걸음이 이 출처로 왔는데 목록에 없어 0걸음으로 실패했다.
            HealthOrigin hcPhone = new HealthOrigin(
                    "com.android.healthconnect.phone.0a1b2c3d4e5f60718293a4b5c6d7e8f9", "AUTO", null);
            EvaluationOutcome outcome = evaluator.evaluate(ctx(wakeConfig("07:00",
                    List.of("com.sec.android.app.shealth", "com.android.healthconnect.phone.*")),
                    List.of(steps(16, at(7, 2), at(7, 3), hcPhone)), List.of(), at(9, 0)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("value", 16L).doesNotContainKey("rejectedOrigins");
        }

        @Test
        @DisplayName("접두사가 아닌 항목은 여전히 정확히 같은 출처만 인정한다")
        void exactEntriesStayExact() {
            HealthOrigin lookalike = new HealthOrigin("com.sec.android.app.shealth.fake", "AUTO", null);
            EvaluationOutcome outcome = evaluator.evaluate(ctx(wakeConfig("07:00",
                    List.of("com.sec.android.app.shealth", "com.android.healthconnect.phone.*")),
                    List.of(steps(100, at(7, 2), at(7, 3), lookalike)), List.of(), at(9, 0)));

            assertThat(outcome.status()).isNotEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("value", 0L);
        }

        @Test
        @DisplayName("창 안에 걸었는데 출처 때문에 전부 빠졌으면 늦잠이 아니라 「인정되지 않는 출처」다")
        void untrustedStepsInsideWindowAreNotLateWakeUp() {
            HealthOrigin unknownApp = new HealthOrigin("com.example.steps", "AUTO", null);
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(40, at(7, 2), at(7, 5), unknownApp)), List.of(), at(9, 0)));

            assertThat(outcome.failureReason()).isEqualTo("UNTRUSTED_HEALTH_SOURCE");
        }

        @Test
        @DisplayName("출처 밖 기록이 창 밖에만 있으면 그대로 늦잠이다")
        void untrustedStepsOutsideWindowStayLate() {
            HealthOrigin unknownApp = new HealthOrigin("com.example.steps", "AUTO", null);
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(steps(40, at(8, 0), at(8, 5), unknownApp)), List.of(), at(9, 0)));

            assertThat(outcome.failureReason()).isEqualTo("WOKE_UP_LATE");
        }

        @Test
        @DisplayName("창이 열려 있고 아직 걸음이 없으면 기다린다")
        void waitsWhileWindowOpen() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(
                    wakeConfig("07:00"), List.of(), List.of(), at(7, 10)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(outcome.failureReason()).isNull();
        }
    }

    @Nested
    @DisplayName("스크린타임 — 앱 형식 이벤트 짝짓기")
    class ScreenTimePairing {

        private final ScreenTimeEvaluator evaluator = new ScreenTimeEvaluator();
        private static final String IG = "com.instagram.android";

        @Test
        @DisplayName("같은 앱 안에서 화면을 옮겨도 사용 시간이 끊기지 않는다 — 앞 화면 STOPPED 가 새 화면을 닫지 않는다")
        void activitySwitchKeepsSession() {
            Instant t = at(21, 0);
            EvaluationOutcome outcome = evaluator.evaluate(ctx(screenTimeConfig(ScreenTimeMode.MIN, 15), List.of(usage(List.of(
                    use(IG, "RESUMED", t),
                    use(IG, "PAUSED", t.plusSeconds(60)),
                    use(IG, "RESUMED", t.plusSeconds(60)),
                    use(IG, "STOPPED", t.plusSeconds(61)),
                    use(IG, "PAUSED", t.plusSeconds(60 + 20 * 60))), at(21, 30))), List.of(), at(21, 30)));

            assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
            assertThat(outcome.evidence()).containsEntry("usageMinutes", 21L);
        }

        @Test
        @DisplayName("닫힘 없이 끊긴 세션은 자정이 아니라 마지막으로 받은 시각에서 닫는다")
        void openSessionClosesAtLastReceipt() {
            // 20:00 에 열고 20:10 에 받은 뒤 기록이 끊겼다(배터리 꺼짐) — 자정까지 4시간을 쓴 것으로 보면 30분 한도 실패가 된다.
            EvaluationOutcome outcome = evaluator.evaluate(ctx(screenTimeConfig(ScreenTimeMode.MAX, 30), List.of(usage(List.of(
                    use(IG, "RESUMED", at(20, 0))), at(20, 10))), List.of(), TARGET.plusDays(1).atStartOfDay(KST).toInstant().plusSeconds(60)));

            assertThat(outcome.failureReason()).isNull();
            assertThat(outcome.evidence()).containsEntry("usageMinutes", 10L);
        }

        @Test
        @DisplayName("PAUSED 없이 늦게 온 STOPPED(강제 종료)는 그대로 세션을 닫는다")
        void lateStopStillCloses() {
            EvaluationOutcome outcome = evaluator.evaluate(ctx(screenTimeConfig(ScreenTimeMode.MAX, 30), List.of(usage(List.of(
                    use(IG, "RESUMED", at(20, 0)),
                    use(IG, "STOPPED", at(20, 40))), at(20, 45))), List.of(), at(20, 50)));

            assertThat(outcome.failureReason()).isEqualTo("USAGE_EXCEEDED");
            assertThat(outcome.evidence()).containsEntry("usageMinutes", 40L);
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
        @DisplayName("Health Connect 가 폰에서 직접 쓴 수면 기록도 접두사 항목으로 인정된다")
        void healthConnectPhoneSleepIsTrustedByPrefix() {
            Instant now = at(23, 0).plusSeconds(10 * 3600);
            HealthOrigin hcPhone = new HealthOrigin(
                    "com.android.healthconnect.phone.0a1b2c3d4e5f60718293a4b5c6d7e8f9", "AUTO", "PHONE");

            assertThat(evaluator.evaluate(ctx(
                    sleepConfig(new BigDecimal("7"), List.of("com.android.healthconnect.phone.*")),
                    List.of(sleep(at(23, 0), at(23, 0).plusSeconds(8 * 3600), hcPhone)),
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
