package com.ruleup.ruleup_backend.routine.domain;

/**
 * 인증을 어디서 받는지(신호 출처의 큰 분류).
 *  - PHONE          : 폰 센서/사용량(지오펜스, GPS, 활동, 수면, 앱 사용시간 등)
 *  - HEALTH_CONNECT : Health Connect 기록(걸음수, 수면, 마음챙김 등)
 *  - EXTERNAL       : 외부 서비스 API(GitHub, Codeforces, RSS, WakaTime 등)
 *  - MANUAL         : 자동 신호 없음 → 사진/그룹 체크
 *
 * 템플릿의 auto_verification_type 은 앞 3개만, user_routine 은 MANUAL 포함 4개.
 */
public enum VerificationType {
    PHONE, HEALTH_CONNECT, EXTERNAL, MANUAL
}
