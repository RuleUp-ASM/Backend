package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * sync 신호 1건(§3.1). type별로 쓰이는 필드만 채워 옴(나머지 null). 모르는 type은 무시.
 * 단일 레코드 + optional 필드 방식(Jackson 다형성 대신 — 단순/멱등 처리 용이).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncSignal(
        String type,                          // SignalType 문자열(미지원이면 무시)
        String observedAt,                    // 신호 관측 시각 ISO
        // GEOFENCE
        List<GeofenceTransition> transitions,
        // LOCATION / RUNNING_SESSION
        List<GeoPoint> points,
        Boolean isMock,
        // RUNNING_SESSION
        String sessionStart,
        String sessionEnd,
        String detectedActivity,              // RUNNING / WALKING
        // SCREEN_TIME
        String date,                          // 대상 날짜
        List<UsageEvent> usageEvents,
        List<ScreenEvent> screenEvents,
        // SLEEP
        List<SleepSegment> segments
) {}
