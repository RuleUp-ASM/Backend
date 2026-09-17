package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * sync 신호 1건(테크스펙 v2 §6.2). type별로 쓰이는 필드만 채워 옴(나머지 null). 모르는 type은 무시.
 * 단일 레코드 + optional 필드 방식(Jackson 다형성 대신 — 단순/멱등 처리 용이).
 *  v2 변경: 움직임이 RUNNING_SESSION → HEALTH(readings)로 교체. RUNNING_SESSION 필드는 Phase 2용으로 보존.
 *
 *  <p>{@code recordId} 는 클라가 붙이는 신호 고유 식별자다(선택). 있으면 그 값으로, 없으면 신호 내용 전체로
 *  중복을 판정한다 — 오프라인 복구·구간 재전송·FCM 기동 후 일괄 전송이 같은 신호를 여러 번 실어 오기 때문이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncSignal(
        String type,                          // SignalType 문자열(미지원이면 무시)
        String recordId,                      // 신호 고유 식별자(선택). 있으면 멱등 판정의 1순위 키
        String observedAt,                    // 신호 관측 시각 ISO
        // GEOFENCE (Android 와이어: events)
        @JsonAlias("events") List<GeofenceTransition> transitions,
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
        @JsonAlias("appEvents") List<UsageEvent> usageEvents,
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

    /** 원본을 다시 읽을 때 서버가 수신 시각을 채워 넣는다. */
    public SyncSignal withReceivedAt(java.time.Instant at) {
        return new SyncSignal(type, recordId, observedAt, transitions, points, isMock, readings,
                sessionStart, sessionEnd, detectedActivity, date, usageEvents, screenEvents, segments, at);
    }
}
