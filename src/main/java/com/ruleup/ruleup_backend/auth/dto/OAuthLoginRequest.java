package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * POST /api/v1/auth/oauth/{provider} 요청 바디 (2026-08-03 계약).
 * - installationId: 앱 설치 단위 UUID — 동일 설치 다계정 가입 차단 판정 키 (adId 대체)
 * - deviceId: 단일 활성 기기 판정 키
 * - deviceInfo: 매 로그인 갱신 저장 (기기 스펙 기반 flushIntervalSec 산정)
 * - permissions: 초기 권한 스냅샷(참고용) — OS 설정에서 언제든 바뀌므로 서버에 저장하지 않는다
 */
@Schema(name = "OAuthLoginRequest", description = "소셜 로그인 요청")
public record OAuthLoginRequest(

        @Schema(description = "제공자가 발급한 인가코드(authorization code).",
                example = "1eN7v0YwQm3Kx...", requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        @Schema(description = "PKCE code_verifier. 인가 요청에 쓴 값과 같아야 한다.",
                example = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String codeVerifier,

        @Schema(description = """
                인가 요청에 쓴 redirect_uri.
                구글은 서버 등록값과 정확히 일치해야 하고(불일치 시 INVALID_REDIRECT_URI),
                카카오는 간편 로그인 경로에서 SDK가 내부 처리하므로 null 이어도 된다.""",
                example = "https://api.ruleup.app/api/v1/auth/oauth/google/callback")
        String redirectUri,

        @Schema(description = """
                앱 설치 단위 UUID. 재설치 전까지 유지한다.
                같은 설치에 이미 활성 계정이 있으면 신규 가입 분기를 막는 판정 키다(동일 기기 다계정 차단).""",
                example = "8f14e45f-ea1d-4c4b-9b2f-1a2b3c4d5e6f",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String installationId,

        @Schema(description = "기기 식별자. 단일 활성 기기 판정 키 — 값이 바뀌면 기존 기기의 세션이 종료된다.",
                example = "d3a1f2b4-77c9-4b1e-9f0a-2c5d8e7b6a10",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String deviceId,

        @Schema(description = "기기 스펙. 필수이며 형식 위반 시 INVALID_DEVICE_INFO 로 거절한다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        DeviceInfoRequest deviceInfo,

        @Schema(description = """
                초기 권한 스냅샷(선택, 참고용). OS 설정에서 언제든 바뀌므로 서버에 저장하지 않는다.
                예: { "location": "GRANTED", "notification": "DENIED" }""",
                example = "{\"location\":\"GRANTED\",\"notification\":\"DENIED\"}")
        Map<String, String> permissions) {}
