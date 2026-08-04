package com.ruleup.ruleup_backend.user.dto;

/** DELETE /api/v1/users/me 요청 — 실수 방지용 고정 확인 문구(계약의 일부). */
public record WithdrawRequest(String confirmPhrase) {}
