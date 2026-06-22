package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 기상 인증 파라미터(§4.3). "beforeTime(예 07:00) 이전 첫 잠금해제"면 성공.
 * 창은 [하루 시작, beforeTime] — beforeTime에 창이 닫힘(자정 아님, §2.11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WakeConfig(
        String beforeTime,            // "HH:mm"
        Polarity polarity,            // ACHIEVEMENT
        int maxSignalLagHours
) {}
