package com.ruleup.ruleup_backend.user.dto;

/**
 * 회원 탈퇴 응답 (회원 탈퇴 API 계약).
 * archiveExpiresAt: 개인정보 아카이브 파기 예정 시각(탈퇴 +1년) — 이 안에 재로그인하면 복원.
 */
public record WithdrawResponse(boolean withdrawn, String archiveExpiresAt, String restoreNote) {}
