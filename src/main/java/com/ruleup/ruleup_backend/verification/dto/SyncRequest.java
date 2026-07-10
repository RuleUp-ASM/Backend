package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.util.List;
import java.util.Map;

/**
 * POST /sync 요청(전송 스펙 §0.1 공통 envelope). Android SyncEnvelopeRequest 에 맞춘다.
 *  - deviceTimeMillis / elapsedRealtimeMillis / bootSessionId : 시각 조작·미래 ts·부트 이전 ts 검증 입력.
 *  - timeZone     : IANA — target_date 귀속.
 *  - permissions  : 신호별 권한 현황(GRANTED/DENIED). network.vpnActive : VPN 게이트 입력(§9.1).
 *  - gaps         : 신호 공백 사유(§0.5) — 서버가 NO_SIGNAL/PERMISSION_MISSING 구분에 사용(§8.5).
 *  - diagnostics/integrity : worker heartbeat·Play Integrity verdict(주기적, 매 flush 아님).
 *  - signals      : 신호 배치(평가 입력).
 *  - 멱등은 verification_daily/method_result upsert((member,date),(daily,method))로 보장되어
 *    오프라인 재전송이 중복 판정을 만들지 않는다.
 *  - 미선언 필드는 ignoreUnknown 으로 무해하게 수용. (permissions/gaps 소비 로직은 §8.5 후속.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncRequest(
        Long deviceTimeMillis,
        Long elapsedRealtimeMillis,
        String bootSessionId,
        String timeZone,
        List<String> activeChallengeIds,
        Map<String, Object> permissions,
        Map<String, Object> diagnostics,
        Map<String, Object> integrity,
        Network network,
        List<SyncSignal> signals,
        List<Gap> gaps
) {
    /** VPN 게이트 입력(§9.1). */
    public record Network(Boolean vpnActive) {}

    /** 신호 공백 사유(§0.5). recoverable=false → 판정 제외, true → 유예. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gap(String signalType, String reason, Long fromMillis, Long toMillis, Boolean recoverable) {}
}
