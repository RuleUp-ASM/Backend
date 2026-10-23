package com.ruleup.ruleup_backend.verification.domain;

import java.util.Map;

/**
 * 인증 방식 → <b>실패 사유 코드</b>. 확정 배치와 조회가 <b>같은 코드</b>를 써야 한다.
 *
 * <p>실패 예정 화면에 「미달」이라고만 뜨고 확정 후에 「INSUFFICIENT_DWELL」이 뜨면, 같은 사건을
 * 두 번 다르게 설명하는 것이다. 공통 5-2 가 실패 사유를 실패 예정 구간에도 요구하는 이유가
 * 그것이다 — 유저는 그 화면을 보고 이의를 낸다.
 */
public final class FailureReasons {

    private FailureReasons() {}

    /** 목표에 못 미쳤을 때의 사유 코드. 판정 <b>불가</b>는 {@link GapReason} 이 따로 다룬다. */
    public static String of(VerificationMethod method, VerificationConfig config) {
        if (method == null) return "NO_SIGNAL_RECEIVED";
        return switch (method) {
            case WAKE -> "WOKE_UP_LATE";
            case SCREEN_TIME -> "INSUFFICIENT_USAGE";
            case GPS_PRESENCE -> "INSUFFICIENT_DWELL";
            case GPS_DISTANCE -> "INSUFFICIENT_DISTANCE";
            case HEALTH -> health(config);
            case SLEEP -> "INSUFFICIENT_SLEEP";
            default -> "NO_SIGNAL_RECEIVED";
        };
    }

    /**
     * 평가 결과가 남긴 <b>판정 불가</b> 표시. 신호가 아예 없었거나 전부 신뢰 게이트에서 빠진
     * 경우라 「목표 미달」과 섞으면 안 된다.
     */
    public static String pendingReasonOf(Map<String, Object> evidence) {
        Object pending = (evidence == null) ? null : evidence.get("pendingReason");
        return (pending == null) ? null : pending.toString();
    }

    private static String health(VerificationConfig config) {
        if (config != null && config.health() != null && config.health().metric() != null) {
            return switch (config.health().metric()) {
                case STEPS -> "INSUFFICIENT_STEPS";
                default -> "INSUFFICIENT_DISTANCE";   // DISTANCE / EXERCISE_DURATION
            };
        }
        return "INSUFFICIENT_DISTANCE";
    }
}
