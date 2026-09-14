package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.verification.domain.VerificationMethod;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.util.List;
import java.util.Set;

/**
 * 판정 방식 → 그 판정이 읽는 신호 타입.
 *
 * <p>두 곳이 같은 답을 내야 한다. 권한 공백(gap)을 방식에 맞춰 해석할 때와, 확정 시점에
 * <b>「신호가 아예 없었다」와 「신호는 있었는데 모자랐다」를 가를 때</b>다. 두 곳이 어긋나면
 * 걸음 신호만 올린 사용자의 장소 인증이 「목표 미달」로 확정된다 — 실제로는 위치 신호를
 * 한 번도 못 받은 것이라 사유가 다르고, 유저에게 보여 줄 안내도 다르다.
 */
public final class MethodSignalTypes {

    private MethodSignalTypes() {}

    /** 그 방식이 입력으로 쓰는 신호 타입(대문자). 와이어 별칭도 함께 담는다. */
    public static Set<String> of(VerificationMethod method) {
        if (method == null) return Set.of();
        return switch (method) {
            case GPS_PRESENCE, GPS_DISTANCE ->
                    Set.of("GEOFENCE", "GEOFENCE_TRANSITION", "LOCATION", "RUNNING_SESSION");
            case HEALTH -> Set.of("HEALTH");
            case SCREEN_TIME -> Set.of("SCREEN_TIME", "USAGE", "APP_USAGE");
            case WAKE -> Set.of("SCREEN_TIME", "WAKE", "UNLOCK", "USAGE");
            case SLEEP -> Set.of("SLEEP");
            default -> Set.of();
        };
    }

    /** 그 방식이 읽을 신호가 하나라도 있는지. 없으면 판정 근거가 아니라 <b>무신호</b>다. */
    public static boolean anyFor(VerificationMethod method, List<SyncSignal> signals) {
        if (signals == null || signals.isEmpty()) return false;
        Set<String> types = of(method);
        if (types.isEmpty()) return false;
        for (SyncSignal s : signals) {
            if (s != null && s.type() != null && types.contains(s.type().toUpperCase())) return true;
        }
        return false;
    }
}
