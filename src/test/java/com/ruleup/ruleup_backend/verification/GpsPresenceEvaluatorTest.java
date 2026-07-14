package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.verification.domain.*;
import com.ruleup.ruleup_backend.verification.evaluator.DayContext;
import com.ruleup.ruleup_backend.verification.evaluator.EvaluationOutcome;
import com.ruleup.ruleup_backend.verification.evaluator.GpsPresenceEvaluator;
import com.ruleup.ruleup_backend.verification.signal.GeoPoint;
import com.ruleup.ruleup_backend.verification.signal.GeofenceTransition;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GpsPresenceEvaluator 단위 테스트(순수 로직, DB/Spring 불필요).
 *  - ① LOCATION fallback 이 배치당 1포인트여도 sync 간 lastInsideAt 이월로 연속 체류를 누적하는지.
 *  - ② 재전송된 트랜지션 배치가 (geofenceId|transition|at) 멱등으로 ENTER/EXIT 를 이중 누적하지 않는지.
 */
class GpsPresenceEvaluatorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 14);
    private final GpsPresenceEvaluator evaluator = new GpsPresenceEvaluator();

    private static GpsConfig visitConfig(int dwellMinutes) {
        return new GpsConfig(
                GpsMode.PRESENCE, GpsPresence.VISIT, AnchorFillMode.MANUAL,
                null, 1500, null, null, 80, dwellMinutes, dwellMinutes,
                null, null, Polarity.ACHIEVEMENT, 1, 100, 50, List.of());
    }

    private DayContext ctx(GpsConfig gps, List<SyncSignal> signals,
                           Map<String, Object> prior, List<GeoAnchor> anchors) {
        VerificationConfig config = new VerificationConfig(
                ScheduleType.FIXED_DAYS, null, MethodCombine.AND,
                List.of(VerificationMethod.GPS_PRESENCE), gps, null, null, null, null, List.of());
        return new DayContext(DAY, KST, Instant.parse("2026-07-14T05:00:00Z"),
                config, signals, prior, anchors, List.of());
    }

    // SyncSignal 13필드: type, observedAt, transitions, points, isMock, readings,
    //                    sessionStart, sessionEnd, detectedActivity, date, usageEvents, screenEvents, segments
    private static SyncSignal geofence(String transition, String atIso) {
        return new SyncSignal("GEOFENCE", atIso,
                List.of(new GeofenceTransition("member-1", transition, atIso, false)),
                null, null, null, null, null, null, null, null, null, null);
    }

    private static SyncSignal location(double lat, double lng, String atIso) {
        return new SyncSignal("LOCATION", atIso, null,
                List.of(new GeoPoint(lat, lng, 10.0, atIso, false)),
                false, null, null, null, null, null, null, null, null);
    }

    // ===== ② 트랜지션 멱등: 재전송된 ENTER/EXIT 가 이중 누적되지 않는다 =====
    @Test
    void resendingSameTransitionsDoesNotDoubleCountDwell() {
        var enter = geofence("ENTER", "2026-07-14T01:00:00Z");
        var exit  = geofence("EXIT",  "2026-07-14T01:40:00Z");   // 40분 체류

        EvaluationOutcome first = evaluator.evaluate(ctx(visitConfig(30), List.of(enter, exit), null, List.of()));
        assertThat(first.status()).isEqualTo(com.ruleup.ruleup_backend.common.verification.VerificationStatus.SUCCESS);
        assertThat(((Number) first.evidence().get("dwellSeconds")).longValue()).isEqualTo(40 * 60L);

        // 같은 배치를 그대로 재전송(ACK 유실 재시도 시나리오). prior=이전 evidence.
        EvaluationOutcome second = evaluator.evaluate(ctx(visitConfig(30), List.of(enter, exit), first.evidence(), List.of()));
        // 40분 그대로여야 함(80분으로 부풀면 안 됨).
        assertThat(((Number) second.evidence().get("dwellSeconds")).longValue()).isEqualTo(40 * 60L);
    }

    // ===== ① LOCATION fallback: 배치당 1포인트라도 sync 간 연속 누적 =====
    @Test
    void locationFallbackAccumulatesAcrossSyncsWithOnePointPerBatch() {
        GeoAnchor anchor = new GeoAnchor(37.5, 127.0, 200, "gym");
        GpsConfig cfg = visitConfig(10);   // 목표 10분

        // 5분 간격 3개 포인트를 각각 별도 sync 로 전송(반경 내).
        Map<String, Object> prior = null;
        String[] times = {"2026-07-14T02:00:00Z", "2026-07-14T02:05:00Z", "2026-07-14T02:10:00Z"};
        EvaluationOutcome out = null;
        for (String t : times) {
            out = evaluator.evaluate(ctx(cfg, List.of(location(37.5, 127.0, t)), prior, List.of(anchor)));
            prior = out.evidence();
        }

        // 02:00 → 02:10 = 10분 누적 → 목표 충족.
        assertThat(((Number) out.evidence().get("dwellSeconds")).longValue()).isEqualTo(10 * 60L);
        assertThat(out.evidence().get("source")).isEqualTo("POINTS");
        assertThat(out.status()).isEqualTo(com.ruleup.ruleup_backend.common.verification.VerificationStatus.SUCCESS);
    }

    // ===== ① 워터마크 멱등: 같은 LOCATION 포인트 재전송은 이중 누적되지 않는다 =====
    @Test
    void resendingSameLocationPointDoesNotDoubleCount() {
        GeoAnchor anchor = new GeoAnchor(37.5, 127.0, 200, "gym");
        GpsConfig cfg = visitConfig(10);

        var p1 = location(37.5, 127.0, "2026-07-14T02:00:00Z");
        var p2 = location(37.5, 127.0, "2026-07-14T02:05:00Z");

        EvaluationOutcome first = evaluator.evaluate(ctx(cfg, List.of(p1, p2), null, List.of(anchor)));
        long firstDwell = ((Number) first.evidence().get("dwellSeconds")).longValue();
        assertThat(firstDwell).isEqualTo(5 * 60L);

        // p2 를 다시 보냄(이미 반영된 시각) → 증가 없어야 함.
        EvaluationOutcome second = evaluator.evaluate(ctx(cfg, List.of(p2), first.evidence(), List.of(anchor)));
        assertThat(((Number) second.evidence().get("dwellSeconds")).longValue()).isEqualTo(firstDwell);
    }
}
