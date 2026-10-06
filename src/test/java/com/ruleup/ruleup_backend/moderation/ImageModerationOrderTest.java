package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.llm.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이미지 심사 순서 — SafeSearch → (판정 불가일 때만) LLM. LLM 안의 Gemini → Nova 폴백은 {@link LlmClient} 가 맡는다.
 */
class ImageModerationOrderTest {

    private static final byte[] IMAGE = {1, 2, 3};

    private final SafeSearchClient safeSearch = mock(SafeSearchClient.class);
    // parseJson 은 인터페이스 기본 메서드라 실제 구현을 그대로 쓴다.
    private final LlmClient llm = mock(LlmClient.class, CALLS_REAL_METHODS);
    private final GeminiModerationClient client = new GeminiModerationClient(llm, safeSearch);

    @Test
    @DisplayName("SafeSearch 가 판정하면 LLM 을 부르지 않는다")
    void safeSearchVerdictIsFinal() {
        when(safeSearch.check(IMAGE)).thenReturn(ModerationResult.REJECTED);

        assertThat(client.moderateImageBytes(IMAGE, "image/png")).isEqualTo(ModerationResult.REJECTED);
        verify(llm, never()).generateText(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("SafeSearch 가 판정 불가면 LLM(Gemini → Nova) 결과를 쓴다")
    void fallsBackToLlmWhenSafeSearchUnavailable() {
        when(safeSearch.check(IMAGE)).thenReturn(ModerationResult.UNAVAILABLE);
        when(llm.generateText(anyString(), any(byte[].class), anyString())).thenReturn("{\"flagged\": true, \"reason\": \"노출\"}");

        assertThat(client.moderateImageBytes(IMAGE, "image/png")).isEqualTo(ModerationResult.REJECTED);
    }

    @Test
    @DisplayName("SafeSearch·LLM 모두 판정 불가면 보류")
    void bothUnavailableIsUnavailable() {
        when(safeSearch.check(IMAGE)).thenReturn(ModerationResult.UNAVAILABLE);
        when(llm.generateText(anyString(), any(byte[].class), anyString())).thenReturn(null);

        assertThat(client.moderateImageBytes(IMAGE, "image/png")).isEqualTo(ModerationResult.UNAVAILABLE);
    }
}
