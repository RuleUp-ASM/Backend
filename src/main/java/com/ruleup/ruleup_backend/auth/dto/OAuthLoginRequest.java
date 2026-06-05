package com.ruleup.ruleup_backend.auth.dto;

/** POST /api/auth/oauth/{provider} 요청 바디. (snake_case ↔ camelCase 자동) */
public record OAuthLoginRequest(String code, String codeVerifier, String redirectUri) {}