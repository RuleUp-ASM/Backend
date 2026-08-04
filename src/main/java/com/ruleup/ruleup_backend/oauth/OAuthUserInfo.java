package com.ruleup.ruleup_backend.oauth;

/**
 * 어떤 IdP든 공통으로 추려낸 사용자 식별 정보.
 * nickname/profileImageUrl 은 온보딩 프리필 힌트로만 쓴다(자동 제출 금지 — check API 통과 필요).
 * 생일·성별 힌트는 받지 않는다(카카오 비즈 앱 미전환·구글 민감 스코프 — 2026-08-03 결정).
 */
public record OAuthUserInfo(String subject, String email, String nickname, String profileImageUrl) {}
