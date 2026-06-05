package com.ruleup.ruleup_backend.auth.dto;

/** POST /api/v1/nicknames/check 요청 바디 (스펙 4.6). */
public record NicknameCheckRequest(String nickname) {}