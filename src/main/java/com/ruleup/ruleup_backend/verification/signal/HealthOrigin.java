package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * HEALTH 신호 신뢰 메타데이터(테크스펙 v2 §6.2, §8.2). 신뢰 게이트의 입력 — readings마다 필수 동봉.
 *  - dataOrigin      : 기록 앱 packageName(예 com.sec.android.app.shealth). 화이트리스트 검증.
 *  - recordingMethod : AUTO|ACTIVE|MANUAL. MANUAL(손입력)이면 서버 거부.
 *  - deviceType      : PHONE|WATCH. WATCH면 신뢰 가중↑(선택).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthOrigin(String dataOrigin, String recordingMethod, String deviceType) {}
