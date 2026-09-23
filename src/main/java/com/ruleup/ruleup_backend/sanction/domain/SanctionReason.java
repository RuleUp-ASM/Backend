package com.ruleup.ruleup_backend.sanction.domain;

/** 제재 사유 코드 — 백오피스 공통 5-3. 유저에게 고지되는 값이라 임의 문자열을 쓰지 않는다. */
public enum SanctionReason {
    ILLEGAL_CONTENT,
    REPORT_CONFIRMED,
    SYSTEM_ABUSE,
    MODERATION_EVASION,
    REPORT_ABUSE,
    OPS_INTERFERENCE,
    /** 자동 트랙 — 부정행위 검출. */
    CHEAT_DETECTED,
    /** 자동 트랙 — 연속 실패. */
    CONSECUTIVE_FAILURE,
    /** 자동 트랙 — 권한 미허용으로 측정 불가. */
    PERMISSION_MISSING
}
