package com.ruleup.ruleup_backend.sanction.domain;

/**
 * 제재의 근거 출처 — <b>검토 근거 추적의 핵심</b>이다.
 * "검토 없이 발동된 잠금·영구 정지 0건" 가드레일 감사가 이 값과 {@code sourceId} 를 역조회한다.
 */
public enum SanctionSource {
    /** 신고 검토 결과. */
    REPORT,
    /** 이상탐지 신호 검토 결과. */
    ANOMALY,
    /** 운영자가 근거 없이 직접 집행 — 잠금·영구 정지에 쓰이면 감사 대상이다. */
    DIRECT
}
