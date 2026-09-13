package com.ruleup.ruleup_backend.verification.domain;

/**
 * 신호를 판정에서 뺀 이유 (공통 5-3 {@code signal_exclusions.reason}).
 *
 * <p><b>다섯 값이 전부 「판정에 쓸 수 없다」는 뜻이지 「속였다」는 뜻이 아니다.</b>
 * 부정행위 확정은 이 값들이 여러 건에 걸쳐 반복될 때 이상패턴 탐지 층이 한다.
 */
public enum SignalExclusionReason {

    /** 조작된 위치. 단건으로는 기기·에뮬레이터 설정일 수 있어 배제만 한다. */
    MOCK,

    /** VPN 활성 구간의 위치 — 다른 나라 좌표로 바뀌어 올 수 있어 근거로 쓰지 않는다. */
    VPN,

    /** 신뢰 목록에 없는 출처의 기록(Health Connect 출처·무결성 검증 실패 기기). */
    UNTRUSTED_SOURCE,

    /** GPS 정확도가 허용 기준보다 나쁜 측위 — 반경 판정을 못 한다. */
    ACCURACY_LOW,

    /** 사람이 직접 입력한 기록. 걸음·거리는 손으로 적을 수 있어 근거가 될 수 없다. */
    MANUAL_ENTRY
}
