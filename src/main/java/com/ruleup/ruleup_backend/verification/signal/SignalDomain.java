package com.ruleup.ruleup_backend.verification.signal;

import java.util.Optional;

/**
 * 신호 <b>저장</b> 도메인 — API 입력 타입 5종을 물리 테이블 셋으로 접는다(백엔드 4-1).
 *
 * <p>입력 타입({@link SignalType})은 그대로 두고 저장만 합친다. 수집 출처와 조회 특성이 가까운
 * 것끼리 묶어야 nullable 컬럼과 쓸모없는 인덱스가 줄고, 최단 1분 sync 상한에서 그 비용이
 * 그대로 곱해지기 때문이다.
 *
 * <p>각 테이블은 {@code observedDate}(KST 귀속일) 기준 <b>일별 파티션</b>이다. 판정 원본은
 * 현재 귀속일과 직전 유예 귀속일만 필요한 hot storage 라, 만료분을 파티션 DROP 으로 걷어낸다.
 */
public enum SignalDomain {

    /** GPS · 지오펜스. 위치정보라 파기 정책이 가장 이르다. */
    LOCATION("verification_location_signals"),

    /** 앱 사용 시간 · 화면 켜짐/잠금해제. <b>전송량의 대부분</b>이 여기로 온다. */
    DEVICE_USAGE("verification_device_usage_signals"),

    /** Health Connect 걸음 · 거리 · 수면. */
    HEALTH_CONNECT("verification_health_connect_signals");

    private final String table;

    SignalDomain(String table) {
        this.table = table;
    }

    public String table() {
        return table;
    }

    /**
     * 입력 타입 → 저장 도메인. <b>모르는 타입은 empty</b> 다 — 평가기가 무시하는 신호를
     * 굳이 보관하지 않는다(계약에 없는 payload 를 쌓는 것은 수집 최소화 원칙에 어긋난다).
     */
    public static Optional<SignalDomain> of(String signalType) {
        if (signalType == null) return Optional.empty();
        return switch (signalType.trim().toUpperCase()) {
            case "LOCATION", "GEOFENCE", "GEOFENCE_TRANSITION", "RUNNING_SESSION" -> Optional.of(LOCATION);
            case "SCREEN_TIME", "WAKE", "UNLOCK", "APP_USAGE" -> Optional.of(DEVICE_USAGE);
            case "HEALTH", "SLEEP", "STEPS", "DISTANCE" -> Optional.of(HEALTH_CONNECT);
            default -> Optional.empty();
        };
    }
}
