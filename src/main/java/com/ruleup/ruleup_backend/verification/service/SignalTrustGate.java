package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.SignalExclusionReason;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 봉투 수준 신뢰 게이트 (백엔드 테크스펙 §4-3 「신호 게이트」).
 *
 * <p>기기 전체를 못 믿을 상황이 두 가지 있다.
 * <ul>
 *   <li><b>VPN 활성</b> — 위치를 다른 나라로 바꿔 보낼 수 있어 그 구간의 위치 신호를 근거로 쓸 수 없다.</li>
 *   <li><b>무결성 검증 실패</b> — 루팅·후킹된 기기라 신호 자체가 조작됐을 수 있다.</li>
 * </ul>
 *
 * <p>둘 다 <b>판정 입력에서 빼기만</b> 한다. 스펙이 "명백한 비정상 신호를 제외하는 것과 사용자를
 * 부정행위자로 확정하는 것을 분리"하라고 못 박았기 때문이다 — 회사 VPN을 켜 둔 사람과 위치를 속이는
 * 사람을 서버는 구분할 수 없다. 제재는 부정행위 영역이 별도 근거로 판단한다.
 *
 * <h4>배제는 요청이 아니라 행에 새긴다</h4>
 * 판정이 raw 를 다시 읽어 전량 재평가하므로, 배제를 이번 요청의 리스트에서 빼는 것으로 끝내면
 * <b>다음 sync 가 같은 신호를 아무 표시 없이 되살린다</b>. 그래서 이 게이트는 「어떤 신호를 어떤
 * 사유로 배제할지」만 정하고, 적재 단계가 그 사유를 행에 적는다. 원본은 그대로 저장한다 —
 * 판정에 안 쓸 뿐 이상탐지 자료로는 남겨야 한다.
 */
@Component
@RequiredArgsConstructor
public class SignalTrustGate {

    private static final Logger log = LoggerFactory.getLogger(SignalTrustGate.class);

    private final SignalExclusionRecorder exclusionRecorder;

    /** 위치 신뢰가 필요한 신호 — VPN·무결성 실패의 영향을 받는다. */
    private static final List<String> LOCATION_TYPES =
            List.of(SignalType.GEOFENCE.name(), "GEOFENCE_TRANSITION", SignalType.LOCATION.name());

    /**
     * 이 요청의 신호에 새길 배제 사유. 봉투를 믿을 수 있으면 null 이고, 그때는 아무 행도 배제되지 않는다.
     *
     * @return 신호 타입 → 배제 사유. 적재 단계가 이 표를 보고 행에 사유를 적는다
     */
    public java.util.function.Function<String, SignalExclusionReason> decide(SyncRequest req) {
        String reason = untrustedReason(req);
        if (reason == null) return type -> null;
        SignalExclusionReason excluded = exclusionReason(reason);
        return type -> (type != null && LOCATION_TYPES.contains(type)) ? excluded : null;
    }

    /** 게이트로 빠진 신호를 배제 로그·관측 지표에 남긴다. 판정에는 영향이 없다. */
    public void record(UUID userId, SyncRequest req, List<SyncSignal> signals) {
        String reason = untrustedReason(req);
        if (reason == null || signals == null || signals.isEmpty()) return;

        List<SyncSignal> dropped = signals.stream()
                .filter(s -> s != null && s.type() != null && LOCATION_TYPES.contains(s.type()))
                .toList();
        if (dropped.isEmpty()) return;

        // 로깅 스펙 §9 #7 — reason 은 MOCK·VPN·UNTRUSTED 중 하나다.
        log.info("gate_dropped userId={} reason={} dropped={} total={}",
                userId, reason, dropped.size(), signals.size());
        // 배제 로그는 이상패턴 탐지의 입력이다(공통 5-3). 여기 기록은 **판정 이전**이라
        // 귀속할 판정이 없다 — 기기 단위 사건이라 유저에만 달아 둔다.
        exclusionRecorder.recordGateDrop(userId, exclusionReason(reason), dropped, Instant.now());
    }

    /** 게이트 사유 → 배제 로그의 사유. VPN 과 무결성 실패는 층이 다르다. */
    private SignalExclusionReason exclusionReason(String reason) {
        return "VPN".equals(reason)
                ? SignalExclusionReason.VPN : SignalExclusionReason.UNTRUSTED_SOURCE;
    }

    /** 위치를 못 믿을 사유. 없으면 null. */
    private String untrustedReason(SyncRequest req) {
        if (req == null) return null;
        if (req.network() != null && Boolean.TRUE.equals(req.network().vpnActive())) return "VPN";
        return integrityFailed(req.integrity()) ? "UNTRUSTED" : null;
    }

    /**
     * Play Integrity verdict 해석. 값을 보내지 않는 클라가 있어 <b>모르면 통과</b>시킨다 —
     * 미전송을 실패로 다루면 구버전 앱 전체가 인증 불가가 된다.
     */
    private boolean integrityFailed(Map<String, Object> integrity) {
        if (integrity == null || integrity.isEmpty()) return false;
        Object verdict = integrity.get("verdict");
        if (verdict == null) verdict = integrity.get("deviceIntegrity");
        if (verdict == null) return false;
        String value = verdict.toString().trim().toUpperCase();
        if (value.isEmpty()) return false;
        // 명시적으로 실패라고 말한 경우에만 막는다.
        return value.equals("FAIL") || value.equals("FAILED") || value.equals("false".toUpperCase())
                || value.contains("NOT_MEET") || value.contains("UNRECOGNIZED");
    }
}
