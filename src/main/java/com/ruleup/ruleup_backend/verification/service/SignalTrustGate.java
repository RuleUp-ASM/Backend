package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.signal.SignalType;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 봉투 수준 신뢰 게이트 (백엔드 테크스펙 §4-3 "신호 게이트").
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
 * <p>원본 신호는 그대로 저장한다. 판정에 안 쓸 뿐 이상탐지 자료로는 남겨야 한다.
 */
@Component
public class SignalTrustGate {

    private static final Logger log = LoggerFactory.getLogger(SignalTrustGate.class);

    /** 위치 신뢰가 필요한 신호 — VPN·무결성 실패의 영향을 받는다. */
    private static final List<String> LOCATION_TYPES =
            List.of(SignalType.GEOFENCE.name(), "GEOFENCE_TRANSITION", SignalType.LOCATION.name());

    /** 판정 입력으로 쓸 신호만 남긴다. 걸러낸 건수는 gate_dropped 로 남겨 제외 비율을 관측한다. */
    public List<SyncSignal> apply(UUID userId, SyncRequest req, List<SyncSignal> signals) {
        String reason = untrustedReason(req);
        if (reason == null || signals == null || signals.isEmpty()) return signals;

        List<SyncSignal> kept = signals.stream()
                .filter(s -> s == null || s.type() == null || !LOCATION_TYPES.contains(s.type()))
                .toList();
        int dropped = signals.size() - kept.size();
        if (dropped > 0) {
            // 로깅 스펙 §9 #7 — reason 은 MOCK·VPN·UNTRUSTED 중 하나다.
            log.info("gate_dropped userId={} reason={} dropped={} kept={}",
                    userId, reason, dropped, kept.size());
        }
        return kept;
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
