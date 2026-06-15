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

    /** LLM(챌린지 기본값 추천·루틴 매칭·콘텐츠 검수). 실패 시 각 기능별 폴백. */
    public record Llm(Gemini gemini) {

        /** Google Gemini. 현재 사용 중(멀티모달 → 텍스트·이미지 검수 모두 가능). */
        public record Gemini(
                String apiKey,
                String model,       // 예: gemini-2.5-flash-lite
                String baseUrl,     // 예: https://generativelanguage.googleapis.com/v1beta
                long timeoutMs      // 동기 호출 타임아웃 (스펙 2.3: 예 5000)
        ) {}
    }
}