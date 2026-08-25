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
        List<SleepSegment> segments
) {}
