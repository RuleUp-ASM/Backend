package com.ruleup.ruleup_backend.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 테스트 전용 LLM fake — 실제 Gemini/Bedrock 호출을 차단한다.
 *
 * <p>기본 provider 는 {@code fallback}(Gemini→Nova)이라, 개발자 머신의 .env 자격증명이 있으면
 * 테스트가 실제로 외부를 호출한다(관측된 429/403). 결과적으로는 각 소비자의 폴백으로 통과하지만
 * CI 지연·불안정·비용을 만든다. 여기서는 "미설정 클라이언트"로 고정해 같은 폴백 경로를 즉시 타게 한다.
 *
 * <p>LLM 응답이 필요한 테스트는 이 빈을 {@code @MockitoBean} 으로 덮어써 원하는 값을 돌려주면 된다.
 * provider 빈 배선 자체를 검증하는 테스트는 {@code app.llm.fake=false} 로 이 빈을 끈다.
 */
@Profile("test")
@Configuration
public class FakeLlmConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.llm.fake", havingValue = "true", matchIfMissing = true)
    public LlmClient fakeLlmClient() {
        return new LlmClient() {
            @Override public boolean isConfigured() { return false; }
            @Override public String generateText(String prompt) { return null; }
            @Override public String generateStructured(String prompt, String schema) { return null; }
            @Override public String generateText(String prompt, byte[] image, String mimeType) { return null; }
        };
    }
}
