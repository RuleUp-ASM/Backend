package com.ruleup.ruleup_backend.user.domain;

/**
 * 계정 롤 — 백오피스 공통 5-1.
 *
 * <p>페이지1은 권한을 나누지 않는다. 운영자냐 아니냐 둘뿐이다. 다만 감사 로그가 조작자를
 * 남기므로, 페이지2에서 롤을 세분화해도 기존 스키마를 깨지 않고 값만 늘리면 된다.
 */
public enum UserRole {
    MEMBER,
    /** 백오피스 접근 권한. <b>개인정보 열람 권한이 붙으므로</b> 부여 자체가 운영 결정이다. */
    OPERATOR
}
