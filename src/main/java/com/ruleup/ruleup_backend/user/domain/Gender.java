package com.ruleup.ruleup_backend.user.domain;

/**
 * 성별 (user_information.gender). API 계약(2026-08-03 확정)은 MALE/FEMALE/NON_BINARY.
 * "미응답" 표현은 정책 합의 전이라 DB·enum 은 NON_BINARY(API 계약)와
 * PREFER_NOT_TO_SAY(DB 정리 문서)를 모두 보유한다 — 확정 시 한쪽을 제거한다.
 */
public enum Gender {
    MALE, FEMALE, NON_BINARY, PREFER_NOT_TO_SAY
}
