package com.ruleup.ruleup_backend.admin.domain;

/**
 * 감사 로그의 조작 종류 — 백오피스 공통 5-3.
 *
 * <p><b>조회도 기록한다.</b> 특히 {@link #SNAPSHOT_VIEW} 는 신고 스냅샷 열람이므로 개인정보
 * 열람에 해당하고, 그래서 다른 조회와 섞지 않고 별도 action 으로 남긴다 — 나중에 "누가 언제
 * 누구의 신고 내용을 봤는지"만 뽑아낼 수 있어야 한다.
 */
public enum AdminAction {
    REPORT_QUEUE_VIEW,
    /** 신고 스냅샷 열람 — 개인정보 열람이라 별도 action 이다. */
    SNAPSHOT_VIEW,
    REPORT_RESOLVE,
    SANCTION_APPLY,
    SANCTION_REVOKE,
    CHALLENGE_CLOSE,
    USER_VIEW,
    ANOMALY_VIEW,
    OUTAGE_RELIEF,
    OPS_NOTICE
}
