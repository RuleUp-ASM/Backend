package com.ruleup.ruleup_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Oauth oauth, Llm llm) {

    public record Jwt(
            String secret,
            long accessTokenTtl,
            long refreshTokenTtl,
            long signupTokenTtl
    ) {}

    public record Oauth(Provider kakao, Provider google) {
        public record Provider(
                String clientId,
                String clientSecret,
                String redirectUri
        ) {}
    }

    /** LLM(챌린지 기본값 추천, 스펙 3.1). 실패 시 폴백(AI_RECOMMENDATION_FAILED). */
    public record Llm(Gemini gemini) {
        public record Gemini(
                String apiKey,
                String model,       // 예: gemini-2.5-flash-lite
                long timeoutMs      // 동기 호출 타임아웃 (스펙 2.3: 예 5000)
        ) {}
    }
}