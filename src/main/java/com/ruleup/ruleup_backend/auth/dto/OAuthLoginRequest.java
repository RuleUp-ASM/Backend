package com.ruleup.ruleup_backend.auth.dto;

/**
 * POST /api/auth/oauth/{provider} 요청 바디. (snake_case ↔ camelCase 자동)
 * deviceInfo는 선택값으로, 기존 사용자 로그인 시 기기 정보를 갱신하는 데 쓰인다(미전송 시 기존값 보존).
 */
public record OAuthLoginRequest(String code, String codeVerifier, String redirectUri,
                                DeviceInfoRequest deviceInfo) {}