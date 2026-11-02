package com.ruleup.ruleup_backend.auth.review;

import java.util.UUID;

/**
 * 설정 {@code app.review-login.codes} 의 한 항목 — 코드 해시 하나가 심사 계정 하나에 묶인다.
 *
 * @param label    사람이 읽는 이름(로그·기동 오류에 쓴다). 코드도 해시도 아니다
 * @param codeHash 정규화한 코드의 SHA-256 소문자 hex
 * @param userId   이 코드로 들어가는 심사 계정
 * @param revoked  폐기 여부 — 폐기된 코드로는 로그인되지 않고, 그 계정의 세션은 스위치와 무관하게 끊긴다
 */
public record ReviewLoginCode(String label, String codeHash, UUID userId, boolean revoked) {}
