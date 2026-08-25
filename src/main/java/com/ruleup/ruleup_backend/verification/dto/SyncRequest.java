package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.util.List;
import java.util.Map;

/**
 * POST /sync 요청(전송 스펙 §0.1 공통 envelope). Android SyncEnvelopeRequest 에 맞춘다.
 *  - deviceTimeMillis / elapsedRealtimeMillis / bootSessionId : 시각 조작·미래 ts·부트 이전 ts 검증 입력.
 *  - timeZone     : IANA — 참고용(판정은 KST 고정).
 *  - coveredFrom/coveredUntil : (필수) "이 구간의 신호를 빠짐없이 담았다"는 선언(epoch millis).
 *    서버는 이걸 누적해 날짜별 커버리지를 계산하고, 귀속일 전 구간이 채워지면 그 시점에 판정을 확정한다.
 *    이 선언이 없으면 "신호가 없다"와 "아직 안 왔다"를 구분할 수 없다.
 *  - backlog      : (선택, 기본 false) 장기 오프라인 복귀분(기록용 과거 구간). true면 레이트리밋에 별도 허용치를 적용해
 *    복구가 막히지 않게 한다.
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
        Long coveredFrom,
        Long coveredUntil,
        Boolean backlog,
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
