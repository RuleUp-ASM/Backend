package com.ruleup.ruleup_backend.score.domain;

/**
 * 원장에 저장하는 사건 종류. 화면 표기(내 티어 상세의 {@code recentChanges[].reason})와는 <b>층이 다르다</b> —
 * 저장은 무엇이 일어났는지, 표기는 사용자에게 뭐라고 부를지다. 매핑은 읽는 쪽이 한다.
 */
public enum ScoreLedgerReason {

    /** 날짜별 자동 인증 성공 반영. */
    DAILY_SUCCESS,

    /** 확정 미달 반영 — 만회가 수학적으로 불가능해진 시점에 확정된다. */
    CONFIRMED_MISS,

    /** 사이클 마감 연속 성공 보너스. */
    STREAK_BONUS,

    /** 사이클 마감 연속 실패 추가 감점. */
    STREAK_PENALTY,

    /** 사건성 감점 — 사이클 한도를 거치지 않는다. {@code incidentType} 이 함께 채워진다. */
    INCIDENT,

    /** 소급 정정으로 만들어진 되돌림. */
    REVERSAL
}
