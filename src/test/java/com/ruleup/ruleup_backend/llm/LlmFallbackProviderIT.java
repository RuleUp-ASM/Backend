package com.ruleup.ruleup_backend.llm;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 provider(fallback) 검증: 활성 LlmClient 는 FallbackLlmClient(Gemini 우선 → Nova 폴백)이며,
 * GeminiClient·BedrockNovaClient 는 조건부 빈에서 빠진다(FallbackLlmClient 가 내부에서 직접 생성해 위임).
 */
@Import(TestcontainersConfiguration.class)
// app.llm.fake=false: 배선 검증이 목적이라 테스트용 fake LlmClient(@Primary)를 끈다.
@SpringBootTest(properties = {"app.llm.provider=fallback", "app.llm.fake=false"})
class LlmFallbackProviderIT {

    @Autowired LlmClient llmClient;
    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("provider=fallback 이면 FallbackLlmClient 가 주입되고 개별 provider 빈은 빠진다")
    void fallbackProviderActive() {
        assertThat(llmClient).isInstanceOf(FallbackLlmClient.class);
        assertThat(ctx.getBeanNamesForType(FallbackLlmClient.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(GeminiClient.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(BedrockNovaClient.class)).isEmpty();
    }
}
