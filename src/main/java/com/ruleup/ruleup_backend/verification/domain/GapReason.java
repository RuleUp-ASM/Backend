package com.ruleup.ruleup_backend.verification.domain;

/**
 * 판정 <b>불가</b> 사유 — 실패 사유와 층이 다르다(공통 5-2 「데이터 부족 사유」).
 *
 * <p>「60분 중 42분을 채웠다」와 「권한이 꺼져 아무것도 못 쟀다」는 유저가 해야 할 일이 전혀
 * 다르다. 앞은 더 하면 되고 뒤는 권한을 켜야 한다. 두 값을 한 컬럼에 섞으면 그 안내가 갈리지
 * 않고, 「권한 미허용 지속」 처리도 대상을 고를 수 없다.
 */
public enum GapReason {

    /** 권한이 없어 측정 자체가 불가능했다. 인증 권한 재허용 고지·2사이클 강퇴가 이 값을 본다. */
    PERMISSION_MISSING,

    /** 권한은 있었는데 판정에 쓸 신호가 없었다 — 미도착이거나 전부 신뢰 게이트에서 빠졌다. */
    NO_SIGNAL;

    /**
     * 실패 사유 코드에서 판정 불가 사유를 뽑는다. <b>목표 미달은 판정 불가가 아니다</b> —
     * 잴 수 있었고 못 미친 것이라 null 이다.
     */
    public static String of(String reasonCode) {
        if (reasonCode == null) return null;
        return switch (reasonCode) {
            case "PERMISSION_MISSING" -> PERMISSION_MISSING.name();
            // 신뢰할 수 없는 출처만 남은 경우도 "쓸 신호가 없다"와 같다 — 직접 입력 기록은
            // 애초에 판정 근거가 될 수 없으므로 유저에게는 신호 없음으로 설명해야 한다.
            case "NO_SIGNAL_RECEIVED", "UNTRUSTED_HEALTH_SOURCE" -> NO_SIGNAL.name();
            default -> null;
        };
    }
}
