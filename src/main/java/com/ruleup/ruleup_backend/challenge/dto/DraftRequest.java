package com.ruleup.ruleup_backend.challenge.dto;

/** POST /api/v1/challenges/draft 요청 — 유일한 입력은 루틴 설명(1~200자). */
public record DraftRequest(String description) {}
