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
    /** 이상탐지 신호 검토 종료 — 제재로 승격하는 경로가 아니라 "봤다"는 기록이다. */
    ANOMALY_REVIEW,
    OUTAGE_RELIEF,
    OPS_NOTICE,
    /** 발행 대기 중인 공지 취소. 이미 적재된 공지는 회수되지 않는다. */
    OPS_NOTICE_CANCEL,

    // ===== CS =====
    INQUIRY_QUEUE_VIEW,
    /**
     * 문의 상세 열람 — <b>개인정보 열람</b>이다. 본문에 계정·기기 정보와 유저가 직접 쓴 사연이
     * 담기므로 신고 스냅샷과 같은 취급을 하고, 목록 조회와 섞지 않는다.
     */
    INQUIRY_VIEW,
    INQUIRY_ANSWER,
    /** 분류 변경 — 유저에게 노출하지 않는 조작이라 기록이 유일한 흔적이다. */
    INQUIRY_RECLASSIFY,

    // ===== 조회 전용 =====
    /** 대시보드 — 가드레일 지표를 본 시각이 남아야 "언제부터 알고 있었나"를 답할 수 있다. */
    DASHBOARD_VIEW,
    SANCTION_LIST_VIEW,
    CHALLENGE_VIEW,
    /** 콘솔 진입 인증 시도. 실패는 DENIED 로 남아 무차별 대입의 흔적이 된다. */
    ADMIN_LOGIN
}
