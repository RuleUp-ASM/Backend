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
 * LLM provider 스위치 검증: app.llm.provider=bedrock 이면 BedrockNovaClient 가 활성 LlmClient 로 주입되고
 * GeminiClient 빈은 생성되지 않는다(코드는 그대로, 조건부 빈으로 교체). 기본(fallback)은 나머지 전체 테스트가 커버.
 */
@Import(TestcontainersConfiguration.class)
// app.llm.fake=false: 배선 검증이 목적이라 테스트용 fake LlmClient(@Primary)를 끈다.
@SpringBootTest(properties = {"app.llm.provider=bedrock", "app.llm.fake=false"})
class LlmProviderSwitchIT {

    @Autowired LlmClient llmClient;
    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("provider=bedrock 이면 BedrockNovaClient 가 주입되고 GeminiClient 는 빈에서 빠진다")
    void bedrockProviderActive() {
        assertThat(llmClient).isInstanceOf(BedrockNovaClient.class);
        assertThat(ctx.getBeanNamesForType(GeminiClient.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(BedrockNovaClient.class)).hasSize(1);
    }
}
