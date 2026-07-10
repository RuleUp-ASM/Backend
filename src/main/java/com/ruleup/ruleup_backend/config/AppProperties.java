package com.ruleup.ruleup_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Oauth oauth, Llm llm, Client client) {

    public record Jwt(
            String secret,
            long accessTokenTtl,
            long refreshTokenTtl,
            long signupTokenTtl
    ) {}

    public record Oauth(Provider kakao, Provider google, String appRedirectUri) {
        public record Provider(
                String clientId,
                String clientSecret,
                String redirectUri
        ) {}
        // appRedirectUri: 서버 콜백이 인가코드를 앱으로 돌려줄 커스텀 스킴 딥링크.
        // (카카오는 https redirect만 허용 → iOS 앱이 https를 직접 못 잡으므로 서버가 중계)
    }

    /** LLM(챌린지 기본값 추천·루틴 매칭·콘텐츠 검수). 실패 시 각 기능별 폴백. */
    /**
     * LLM 설정. provider 로 활성 구현을 고른다(gemini 기본 / bedrock). 각 provider 코드는 지우지 않고
     * 조건부 빈으로 스위치만 한다 — 토큰 대비 성능 비교 후 최적 모델 채택.
     */
    public record Llm(String provider, Gemini gemini, Bedrock bedrock) {

        /** Google Gemini(멀티모달 → 텍스트·이미지 검수 모두 가능). provider=gemini(기본)일 때 활성. */
        public record Gemini(
                String apiKey,
                String model,       // 예: gemini-2.5-flash-lite
                String baseUrl,     // 예: https://generativelanguage.googleapis.com/v1beta
                long timeoutMs,     // 동기 호출 타임아웃 (스펙 2.3: 예 5000)
                int maxRetries,     // 일시 오류(503/429/타임아웃) 재시도 횟수. 0/미설정 → 기본값
                long retryBackoffMs // 재시도 사이 대기(지수 백오프 base, ms). 0/미설정 → 기본값
        ) {}

        /** AWS Bedrock Nova(Converse API). provider=bedrock 일 때 활성. 자격증명은 ECS 태스크 역할. */
        public record Bedrock(
                String region,      // 예: ap-northeast-2 서울 (미설정 시 기본 ap-northeast-2)
                String modelId,     // 예: apac.amazon.nova-lite-v1:0 (APAC 온디맨드 크로스리전 추론 프로파일)
                long timeoutMs,     // Converse 호출 총 타임아웃(ms). 0/미설정 → 기본값
                int maxRetries,     // 쓰로틀 재시도 횟수. 0/미설정 → 기본값
                long retryBackoffMs // 재시도 백오프 base(ms). 0/미설정 → 기본값
        ) {}
    }

    /** 앱 버전 게이트(GET /intro). 강제/권장 업데이트 안내값. */
    public record Client(
            int minVersionCode,         // 이 코드 미만이면 강제 업데이트(400)
            String minAppVersion,       // 화면 표시용 최소 버전명 (예: "1.0.0")
            String recommendAppVersion, // 화면 표시용 권장 버전명 (예: "1.2.0")
            String devTestMsg           // 개발/점검용 안내 메시지 (없으면 빈 문자열)
    ) {}
}