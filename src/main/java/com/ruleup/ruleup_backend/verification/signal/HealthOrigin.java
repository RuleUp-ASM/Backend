package com.ruleup.ruleup_backend.verification.signal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * HEALTH 신호 신뢰 메타데이터(테크스펙 v2 §6.2, §8.2). 신뢰 게이트의 입력 — readings마다 필수 동봉.
 *  - dataOrigin      : 기록 앱 packageName(예 com.sec.android.app.shealth). 화이트리스트 검증.
 *  - recordingMethod : AUTO|ACTIVE|MANUAL. MANUAL(손입력)이면 서버 거부.
 *  - deviceType      : PHONE|WATCH. WATCH면 신뢰 가중↑(선택).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthOrigin(String dataOrigin, String recordingMethod, String deviceType) {

    /**
     * 객체로 온 출처가 있으면 그것을, 없으면 Android 와이어의 평평한 두 필드로 조립한다.
     * 둘 다 없으면 null — 출처 누락은 평가기가 판단한다.
     */
    public static HealthOrigin orFlat(HealthOrigin origin, String originPackage, String recordingMethod) {
        if (origin != null) return origin;
        boolean hasPackage = originPackage != null && !originPackage.isBlank();
        boolean hasMethod = recordingMethod != null && !recordingMethod.isBlank();
        return (hasPackage || hasMethod) ? new HealthOrigin(originPackage, recordingMethod, null) : null;
    }
}
