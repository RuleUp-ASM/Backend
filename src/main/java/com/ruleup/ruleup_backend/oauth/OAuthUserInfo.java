package com.ruleup.ruleup_backend.oauth;

/** 어떤 IdP든 공통으로 추려낸 사용자 식별 정보. */
public record OAuthUserInfo(String subject, String email, String profileImageUrl) {}