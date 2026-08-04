package com.ruleup.ruleup_backend.user.domain;

/**
 * 계정 상태 (회원 정책 · DB 정리 문서 users.status).
 *  - ACTIVE    : 정상 이용
 *  - LOCKED    : 계정 잠금(열람 전용) — 로그인은 허용, 행동 차단 (회원 정책 §7)
 *  - BANNED    : 영구 정지 — 로그인·재가입 차단 (403 ACCOUNT_BANNED)
 *  - WITHDRAWN : 소프트 탈퇴 — deleted_at 기록, 1년 내 동일 소셜 계정 재로그인 시 복원
 */
public enum UserStatus {
    ACTIVE, LOCKED, BANNED, WITHDRAWN
}
