package com.ruleup.ruleup_backend.verification.signal;

/**
 * sync 신호 타입 — <b>API 입력 5종</b>(인증 공통 1절 · 백엔드 4-1).
 *
 * <p>{@code LOCATION · HEALTH · SCREEN_TIME · WAKE · SLEEP} 이 계약이고, 물리 저장만
 * {@link SignalDomain} 셋으로 접힌다. {@code GEOFENCE} 는 LOCATION 계열의 전환 이벤트다.
 *
 * <p>{@code WAKE} 는 화면 켜짐·잠금해제를 담는다. 앱 사용 시간(SCREEN_TIME)과 기상은 같은
 * UsageStats 에서 오지만 <b>다른 판정</b>이라, 기상만 보내는 기기가 앱 사용 이벤트를 억지로
 * 싣지 않아도 되도록 입력 타입을 나눠 둔다. 기상 평가기는 둘 다 읽는다.
 *
 * <p>RUNNING_SESSION 은 Phase 2 하이브리드(커스텀 세션) 전용 — MVP에서는 들어와도 평가 안 함(보존만).
 * 그 외/미지원 타입은 ignoredSignalTypes로 회신해 무시.
 */
public enum SignalType { GEOFENCE, LOCATION, HEALTH, SCREEN_TIME, WAKE, SLEEP, RUNNING_SESSION }
