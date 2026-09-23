package com.ruleup.ruleup_backend.applink;

/**
 * 링크가 유효하지 않은 이유. <b>에러가 아니라 판정 결과</b>라 200 응답의 필드로 내려간다 —
 * 클라가 안내 화면을 고르는 근거다.
 */
public enum AppLinkCheckReason {

    /** 우리 서비스의 링크 형식이 아니다. 타입도 토큰도 알 수 없다. */
    MALFORMED,

    /** 우리 도메인이지만 지원하지 않는 링크 타입이다. */
    UNSUPPORTED,

    /** 링크가 가리키는 대상이 없다 — 위조됐거나 삭제됐다. */
    NOT_FOUND,

    /** 토큰의 유효기간이 지났다. */
    EXPIRED
}
