package com.ruleup.ruleup_backend.auth.dto;

import java.util.Map;

/**
 * POST /api/v1/auth/oauth/{provider} 요청 바디 (2026-08-03 계약).
 * - installationId: 앱 설치 단위 UUID — 동일 설치 다계정 가입 차단 판정 키 (adId 대체)
 * - deviceId: 단일 활성 기기 판정 키
 * - deviceInfo: 매 로그인 갱신 저장 (기기 스펙 기반 flushIntervalSec 산정)
 * - permissions: 초기 권한 스냅샷(참고용) — OS 설정에서 언제든 바뀌므로 서버에 저장하지 않는다
 */
public record OAuthLoginRequest(String code, String codeVerifier, String redirectUri,
                                String installationId, String deviceId,
                                DeviceInfoRequest deviceInfo,
                                Map<String, String> permissions) {}
