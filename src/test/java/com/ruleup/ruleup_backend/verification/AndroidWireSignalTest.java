package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.evaluator.*;
import com.ruleup.ruleup_backend.verification.signal.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Android 전송 스펙 모양의 HEALTH·SLEEP·WAKE 신호가 서버 모양으로 접히는지(QA SIG-19 후속).
 *
 * <p>본문은 Android {@code SyncRequest.kt} 의 직렬화 결과 그대로다 — 시각은 epoch millis, 출처는
 * 평평한 두 필드, HEALTH 지표는 신호 단위, 수면은 {@code sessions}, 기상은 {@code firstUnlock}.
 * 접히지 않으면 세 방식 모두 신호가 판정에 들어가지 못한다.
 */
class AndroidWireSignalTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SHEALTH = "com.sec.android.app.shealth";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static long millis(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(KST).toInstant().toEpochMilli();
    }

    private static SyncSignal parse(String json) {
        return JSON.readValue(json, SyncSignal.class);
    }

    @Test
    @DisplayName("HEALTH — 신호 단위 metric 이 reading 마다 채워지고 평평한 출처가 origin 으로 조립된다")
    void healthMetricAndOriginAreFolded() {
        SyncSignal s = parse("""
                {"type":"HEALTH","date":"2026-09-17","metric":"STEPS","readings":[
                  {"recordId":"r1","value":8200.0,"startTime":%d,"endTime":%d,
                   "recordingMethod":"AUTO","originPackage":"%s"}]}
                """.formatted(millis(17, 9, 0), millis(17, 10, 0), SHEALTH));

        HealthReading r = s.readings().getFirst();
        assertThat(r.metric()).isEqualTo("STEPS");
        assertThat(r.origin()).isEqualTo(new HealthOrigin(SHEALTH, "AUTO", null));
        assertThat(TimeWindows.parseInstant(r.startTime()))
                .isEqualTo(Instant.ofEpochMilli(millis(17, 9, 0)));
    }

    @Test
    @DisplayName("HEALTH — 서버 모양 본문은 그대로다(reading 의 metric·origin 을 덮지 않는다)")
    void serverShapedHealthIsUntouched() {
        SyncSignal s = parse("""
                {"type":"HEALTH","metric":"DISTANCE","readings":[
                  {"metric":"STEPS","value":1,"origin":{"dataOrigin":"x","recordingMethod":"ACTIVE"}}]}
                """);

        assertThat(s.readings().getFirst().metric()).isEqualTo("STEPS");
        assertThat(s.readings().getFirst().origin().dataOrigin()).isEqualTo("x");
    }

    @Test
    @DisplayName("WAKE — firstUnlock·firstScreenOn 이 UNLOCK·SCREEN_ON 화면 이벤트가 된다")
    void wakeFirstUnlockBecomesScreenEvent() {
        SyncSignal s = parse("""
                {"type":"WAKE","firstUnlock":%d,"firstScreenOn":%d,"deviceSecure":true}
                """.formatted(millis(17, 6, 30), millis(17, 6, 25)));

        assertThat(s.screenEvents()).extracting(ScreenEvent::event).containsExactly("UNLOCK", "SCREEN_ON");
        assertThat(TimeWindows.parseInstant(s.screenEvents().getFirst().at()))
                .isEqualTo(Instant.ofEpochMilli(millis(17, 6, 30)));
    }

    @Test
    @DisplayName("SLEEP — sessions 가 segments 로 접히고, 평가기가 그 밤을 인증한다")
    void sleepSessionsAreEvaluated() {
        SyncSignal wire = parse("""
                {"type":"SLEEP","sessions":[
                  {"recordId":"s1","start":%d,"end":%d,"durationMillis":28800000,
                   "observedElapsedMillis":1000,"recordingMethod":"AUTO","originPackage":"%s"}]}
                """.formatted(millis(17, 23, 0), millis(18, 7, 0), SHEALTH));

        SleepSegment seg = wire.segments().getFirst();
        assertThat(seg.origin()).isEqualTo(new HealthOrigin(SHEALTH, "AUTO", null));

        // 저장 후 다시 읽을 때처럼 수신 시각을 붙여 평가한다(미래 구간 차단 기준).
        Instant morning = Instant.ofEpochMilli(millis(18, 8, 0));
        VerificationConfig config = new VerificationConfig(ScheduleType.FIXED_DAYS, null,
                MethodCombine.AND, List.of(VerificationMethod.SLEEP), null, null, null, null,
                new SleepConfig(null, BigDecimal.valueOf(7), Polarity.ACHIEVEMENT, 12, List.of(SHEALTH)),
                List.of());
        EvaluationOutcome outcome = new SleepEvaluator(
                new com.ruleup.ruleup_backend.verification.config.VerificationProperties(
                        null, null, null, null, null, null, null, null, null, null, false, false))
                .evaluate(new DayContext(LocalDate.of(2026, 9, 17), KST, morning, config,
                        List.of(wire.withReceivedAt(morning)), List.of(), List.of(), "member"));

        assertThat(outcome.status()).isEqualTo(VerificationStatus.SUCCESS);
    }

    @Test
    @DisplayName("접힌 모양으로 저장했다가 다시 읽어도 같다 — 재평가가 같은 판정을 낸다")
    void foldedShapeRoundTrips() {
        SyncSignal wire = parse("""
                {"type":"HEALTH","metric":"STEPS","readings":[
                  {"value":10,"startTime":1,"endTime":2,"recordingMethod":"AUTO","originPackage":"p"}]}
                """);

        assertThat(parse(JSON.writeValueAsString(wire))).isEqualTo(wire);
    }
}
