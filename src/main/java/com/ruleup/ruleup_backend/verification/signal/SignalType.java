package com.ruleup.ruleup_backend.verification.signal;

/**
 * sync 신호 타입(테크스펙 v2 §6.1).
 *  - MVP 활성: GEOFENCE, LOCATION, HEALTH, SCREEN_TIME, SLEEP.
 *  - RUNNING_SESSION: Phase 2 하이브리드(커스텀 세션) 전용 — MVP에서는 들어와도 평가 안 함(보존만).
 * 그 외/미지원 타입은 ignoredSignalTypes로 회신해 무시.
 */
public enum SignalType { GEOFENCE, LOCATION, HEALTH, SCREEN_TIME, SLEEP, RUNNING_SESSION }
