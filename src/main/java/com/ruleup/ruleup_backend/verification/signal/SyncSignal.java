package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * sync 신호 1건(테크스펙 v2 §6.2). type별로 쓰이는 필드만 채워 옴(나머지 null). 모르는 type은 무시.
 * 단일 레코드 + optional 필드 방식(Jackson 다형성 대신 — 단순/멱등 처리 용이).
 *  v2 변경: 움직임이 RUNNING_SESSION → HEALTH(readings)로 교체. RUNNING_SESSION 필드는 Phase 2용으로 보존.
 *
 *  <p>{@code recordId} 는 클라가 붙이는 신호 고유 식별자다(선택). 있으면 그 값으로, 없으면 신호 내용 전체로
 *  중복을 판정한다 — 오프라인 복구·구간 재전송·FCM 기동 후 일괄 전송이 같은 신호를 여러 번 실어 오기 때문이다.
 *
 * <h4>Android 와이어는 {@link #fromWire} 가 서버 모양으로 접는다</h4>
 * 앱의 전송 스펙은 필드 배치가 다르다(QA SIG-19 후속). 받는 자리에서 한 번 접어 두면 평가기·저장·재평가가
 * 모두 서버 모양 하나만 보면 된다 — 저장도 접힌 모양으로 되므로 다시 읽을 때 같은 변환이 또 필요 없다.
 * <ul>
 *   <li>GEOFENCE: {@code events} → {@code transitions}</li>
 *   <li>SCREEN_TIME: {@code appEvents} → {@code usageEvents}</li>
 *   <li>HEALTH: 신호 단위 {@code metric} → reading 마다. 서버는 reading 의 metric 으로 지표를 가른다</li>
 *   <li>SLEEP: {@code sessions} → {@code segments}</li>
 *   <li>WAKE: {@code firstUnlock}·{@code firstScreenOn}(epoch millis) → {@code screenEvents} 의 UNLOCK·SCREEN_ON</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncSignal(
        String type,                          // SignalType 문자열(미지원이면 무시)
        String recordId,                      // 신호 고유 식별자(선택). 있으면 멱등 판정의 1순위 키
        String observedAt,                    // 신호 관측 시각 ISO
        // GEOFENCE (Android 와이어: events)
        List<GeofenceTransition> transitions,
        // LOCATION / RUNNING_SESSION
        List<GeoPoint> points,
        Boolean isMock,
        // HEALTH (v2 신규 — 움직임)
        List<HealthReading> readings,
        // RUNNING_SESSION (Phase 2 하이브리드 전용 — MVP 무시)
        String sessionStart,
        String sessionEnd,
        String detectedActivity,              // RUNNING / WALKING
        // SCREEN_TIME / HEALTH
        String date,                          // 대상 날짜 YYYY-MM-DD
        List<UsageEvent> usageEvents,
        List<ScreenEvent> screenEvents,
        // SLEEP
        List<SleepSegment> segments,
        /**
         * <b>서버가 이 신호를 받은 시각.</b> 원본을 다시 읽을 때 서버가 행에서 읽어 채워 넣는다.
         *
         * <p>이 필드는 요청 본문으로도 들어올 수 있다 — 그래서 <b>읽는 쪽이 현재 시각으로 상한을
         * 건다</b>(둘 중 이른 값). 클라이언트가 미래 시각을 적어 보내도 이득이 없고, 저장된 값은
         * 항상 현재보다 과거라 그대로 쓰인다.
         *
         * <p>「그때 그 주장이 성립했는가」를 <b>지금</b>이 아니라 <b>받은 때</b>로 따져야 하는
         * 판정이 있다. 수면은 아직 오지 않은 구간을 미리 올려 두면 즉시 평가에서는 걸리지만,
         * 시간이 지난 뒤 마감 재평가가 같은 원본을 다시 읽으면 더 이상 미래가 아니라서
         * 성공 근거로 되살아났다 — 새 기록이 하나도 오지 않았는데도 그렇다.
         */
        java.time.Instant receivedAt
) {

    @JsonCreator
    public static SyncSignal fromWire(
            @JsonProperty("type") String type,
            @JsonProperty("recordId") String recordId,
            @JsonProperty("observedAt") String observedAt,
            @JsonProperty("transitions") @JsonAlias("events") List<GeofenceTransition> transitions,
            @JsonProperty("points") List<GeoPoint> points,
            @JsonProperty("isMock") Boolean isMock,
            @JsonProperty("readings") List<HealthReading> readings,
            @JsonProperty("sessionStart") String sessionStart,
            @JsonProperty("sessionEnd") String sessionEnd,
            @JsonProperty("detectedActivity") String detectedActivity,
            @JsonProperty("date") String date,
            @JsonProperty("usageEvents") @JsonAlias("appEvents") List<UsageEvent> usageEvents,
            @JsonProperty("screenEvents") List<ScreenEvent> screenEvents,
            @JsonProperty("segments") @JsonAlias("sessions") List<SleepSegment> segments,
            @JsonProperty("receivedAt") java.time.Instant receivedAt,
            // ↓ Android 와이어에만 있는 필드. 위 필드로 접고 따로 저장하지 않는다.
            @JsonProperty("metric") String metric,
            @JsonProperty("firstUnlock") String firstUnlock,
            @JsonProperty("firstScreenOn") String firstScreenOn) {
        return new SyncSignal(type, recordId, observedAt, transitions, points, isMock,
                withMetric(readings, metric), sessionStart, sessionEnd, detectedActivity, date,
                usageEvents, withWakeEvents(screenEvents, firstUnlock, firstScreenOn), segments, receivedAt);
    }

    /** reading 에 지표가 없으면 신호 단위 지표를 채운다. 이미 있는 값은 건드리지 않는다. */
    private static List<HealthReading> withMetric(List<HealthReading> readings, String metric) {
        if (readings == null || metric == null || metric.isBlank()) return readings;
        List<HealthReading> out = new ArrayList<>(readings.size());
        for (HealthReading r : readings) {
            out.add((r == null || r.metric() != null) ? r
                    : new HealthReading(r.recordId(), metric, r.value(), r.unit(), r.startTime(), r.endTime(),
                            r.exerciseType(), r.origin()));
        }
        return out;
    }

    /**
     * 앱의 기상 신호는 「그날 첫 잠금 해제·첫 화면 켜짐」 시각 두 개다. 평가기는 화면 이벤트 목록에서
     * 창 안의 첫 UNLOCK 을 찾으므로 같은 뜻의 이벤트로 옮긴다.
     */
    private static List<ScreenEvent> withWakeEvents(List<ScreenEvent> events, String firstUnlock,
                                                    String firstScreenOn) {
        boolean unlock = firstUnlock != null && !firstUnlock.isBlank();
        boolean screenOn = firstScreenOn != null && !firstScreenOn.isBlank();
        if (!unlock && !screenOn) return events;
        List<ScreenEvent> out = (events != null) ? new ArrayList<>(events) : new ArrayList<>();
        if (unlock) out.add(new ScreenEvent("UNLOCK", firstUnlock));
        if (screenOn) out.add(new ScreenEvent("SCREEN_ON", firstScreenOn));
        return out;
    }

    /** 원본을 다시 읽을 때 서버가 수신 시각을 채워 넣는다. */
    public SyncSignal withReceivedAt(java.time.Instant at) {
        return new SyncSignal(type, recordId, observedAt, transitions, points, isMock, readings,
                sessionStart, sessionEnd, detectedActivity, date, usageEvents, screenEvents, segments, at);
    }
}
