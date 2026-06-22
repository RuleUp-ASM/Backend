package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * GPS 인증 파라미터(§4.3). PRESENCE(지오펜스 체류) / DISTANCE(러닝 누적거리) 공용.
 *  - PRESENCE: lat/lng/radiusM/dwellMinutes/loiteringDelayMin 사용.
 *  - DISTANCE: goalKm 사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GpsConfig(
        GpsMode mode,
        Double lat,
        Double lng,
        Integer radiusM,
        Integer dwellMinutes,
        Integer loiteringDelayMin,
        BigDecimal goalKm,
        String timeWindow,            // "HH:mm-HH:mm" (없으면 null)
        WindowAnchor windowAnchor,    // 종속 창(없으면 null)
        Polarity polarity,            // PRESENCE/DISTANCE = ACHIEVEMENT
        int maxSignalLagHours,
        Integer accuracyMaxM,
        Integer outlierThreshold,
        List<String> wifiAnchors
) {}
