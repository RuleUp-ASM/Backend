package com.ruleup.ruleup_backend.verification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/**
 * GPS 인증 파라미터(테크스펙 v2 §5, §12.3). PRESENCE(지오펜스 체류) 주경로.
 *  v2 변경:
 *   - presence(VISIT/AVOID) 추가 → AVOID는 제약형(창 내 ENTER 없으면 SUCCESS).
 *   - 앵커 좌표(anchors[])는 config가 아니라 challenge_members.anchors(PER_MEMBER)에 저장.
 *     config의 GPS는 반경·dwell·fillMode 등 "규칙"만 보유(lat/lng는 단일앵커 레거시·fallback용으로만 잔존).
 *   - DISTANCE(커스텀 세션)는 Phase 2 — 평가 라우팅 안 함. goalKm는 Phase 2 보존.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GpsConfig(
        GpsMode mode,
        GpsPresence presence,          // VISIT / AVOID (v2)
        AnchorFillMode anchorFillMode, // MANUAL / NEARBY_BRAND — 생성 기본 프리셋(v2)
        String brandKeyword,           // NEARBY_BRAND일 때 검색어(v2)
        Integer nearbyRadiusM,         // NEARBY_BRAND 검색 반경(기본 1500, v2)
        Double lat,                    // 레거시 단일앵커(없으면 null) — 평가는 멤버 anchors[] 우선
        Double lng,
        Integer radiusM,               // 기본 반경(앵커가 개별 반경을 가지면 그게 우선)
        Integer dwellMinutes,
        Integer loiteringDelayMin,
        BigDecimal goalKm,             // DISTANCE(Phase 2 보존)
        String timeWindow,             // "HH:mm-HH:mm" (없으면 null)
        Polarity polarity,             // VISIT=ACHIEVEMENT / AVOID=CONSTRAINT
        int maxSignalLagHours,
        Integer accuracyMaxM,
        Integer outlierThreshold,
        List<String> wifiAnchors
) {
    public boolean isAvoid() { return presence == GpsPresence.AVOID; }
}
