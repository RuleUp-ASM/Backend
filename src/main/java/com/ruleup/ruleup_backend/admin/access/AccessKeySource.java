package com.ruleup.ruleup_backend.admin.access;

/**
 * Access 공개키 묶음(JWKS) 원문을 가져온다. 운영은 팀 도메인의 {@code /cdn-cgi/access/certs},
 * 시험은 직접 만든 키로 대신한다 — 검증 로직은 그대로 두고 키의 출처만 바꾼다.
 */
@FunctionalInterface
public interface AccessKeySource {

    String fetchJwks();
}
