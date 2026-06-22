package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 챌린지 인증 설정 (인증 스펙 §4.3). challenges.verificationConfig(JSON)에 저장.
 *  - 챌린지 생성 시 루틴 template param_schema + 사용자 목표값으로 채운다.
 *  - 평가기는 이 config를 기준으로 신호를 판정한다(신뢰 경계: 기준은 서버 소유).
 *  - MVP는 단일 method라 methods.size()==1, methodCombine=AND 기본.
 * verification/domain은 다른 도메인을 import하지 않는 leaf (challenge·routine이 이걸 의존).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationConfig(
        ScheduleType scheduleType,         // FIXED_DAYS / FREQUENCY
        Frequency frequency,               // FREQUENCY일 때만 (그 외 null)
        MethodCombine methodCombine,       // 기본 AND
        List<VerificationMethod> methods,  // 활성 인증 방식(MVP: 1개)
        GpsConfig gps,                     // GPS_PRESENCE/DISTANCE일 때 (그 외 null)
        ScreenTimeConfig screenTime,       // SCREEN_TIME일 때
        WakeConfig wake,                   // WAKE일 때
        SleepConfig sleep,                 // SLEEP일 때
        List<String> requiredPermissions   // 클라 표시용(템플릿에서)
) {
    /** 활성 method 중 첫 번째(MVP 단일). */
    public VerificationMethod primaryMethod() {
        return (methods != null && !methods.isEmpty()) ? methods.get(0) : null;
    }

    /** 수동 인증(PHOTO/SELF_CHECK)인지 — 자동 신호 평가 대상이 아님. */
    public boolean isManual() {
        VerificationMethod m = primaryMethod();
        return m == VerificationMethod.PHOTO || m == VerificationMethod.SELF_CHECK;
    }

    public boolean isFrequency() { return scheduleType == ScheduleType.FREQUENCY; }

    public boolean hasMethod(VerificationMethod m) {
        return methods != null && methods.contains(m);
    }
}
