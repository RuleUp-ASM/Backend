package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 스크린타임 인증 파라미터(§4.3). MIN(도달형)/MAX(제약형).
 * usageEvents(RESUMED/PAUSED)를 페어링해 timeWindow 교집합 합산 → goalMinutes와 비교.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScreenTimeConfig(
        ScreenTimeMode mode,
        Polarity polarity,            // MIN=ACHIEVEMENT / MAX=CONSTRAINT
        List<String> targetPackages,
        Integer goalMinutes,          // MAX·goalMinutes=0 이면 폰 금지창
        String timeWindow,            // "HH:mm-HH:mm" (없으면 하루)
        WindowAnchor windowAnchor,
        int maxSignalLagHours
) {}
