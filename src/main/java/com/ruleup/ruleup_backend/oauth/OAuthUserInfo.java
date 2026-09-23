package com.ruleup.ruleup_backend.oauth;

import java.time.Instant;

/**
 * 어떤 IdP든 공통으로 추려낸 사용자 식별 정보 + IdP 토큰.
 * nickname/profileImageUrl 은 온보딩 프리필 힌트로만 쓴다(자동 제출 금지 — check API 통과 필요).
 * 생일·성별 힌트는 받지 않는다(카카오 비즈 앱 미전환·구글 민감 스코프 — 2026-08-03 결정).
 * idpTokens 는 social_tokens 암호화 저장(unlink 근거)용 — 응답으로 절대 내려주지 않는다.
 */
public record OAuthUserInfo(String subject, String email, String nickname, String profileImageUrl,
                            IdpTokens idpTokens) {

    /** IdP가 발급한 토큰 원문(저장 시 애플리케이션 암호화). */
    public record IdpTokens(String accessToken, String refreshToken, Instant expiresAt) {}
}
