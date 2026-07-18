package com.ruleup.ruleup_backend.llm;

import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini 우선 → 실패 시 Bedrock Nova 폴백 {@link LlmClient}.
 * {@code app.llm.provider=fallback}(기본) 일 때 활성화된다.
 *
 * <p>동기: 100케이스×5모델 평가에서 gemini-3.1-flash-lite 가 정확도 1위(응답분 100%)이면서
 * 토큰·지연도 우수해 1순위로, Nova(Lite)가 Bedrock 내부에서 다음으로 견고해 폴백으로 뽑혔다.
 * Gemini 가 null(키 미설정·429 소진·타임아웃·빈 응답)을 주면 그 호출만 Nova 로 넘긴다.
 *
 * <p>{@link GeminiClient}·{@link BedrockNovaClient} 는 각각 조건부 빈이라 provider=fallback 에서는
 * 빈으로 뜨지 않는다 — 여기서 직접 생성해 위임한다(둘 다 AppProperties 만 필요). 실패는 예외 없이
 * null 반환 → 호출 측이 각자 폴백(none/empty/UNAVAILABLE).
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "fallback", matchIfMissing = true)
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);

    private final GeminiClient primary;   // Gemini 우선
    private final BedrockNovaClient fallback; // Nova 폴백

    public FallbackLlmClient(AppProperties props) {
        this.primary = new GeminiClient(props);
        this.fallback = new BedrockNovaClient(props);
        log.info("LLM provider=fallback (primary=gemini configured={}, fallback=nova configured={})",
                primary.isConfigured(), fallback.isConfigured());
    }

    /** 둘 중 하나라도 호출 가능하면 true. */
    @Override
    public boolean isConfigured() {
        return primary.isConfigured() || fallback.isConfigured();
    }

    @Override
    public String generateText(String prompt) {
        String r = primary.isConfigured() ? primary.generateText(prompt) : null;
        if (r != null) return r;
        return novaFallback("generateText", () -> fallback.generateText(prompt));
    }

    @Override
    public String generateStructured(String prompt, String responseSchemaJson) {
        String r = primary.isConfigured() ? primary.generateStructured(prompt, responseSchemaJson) : null;
        if (r != null) return r;
        return novaFallback("generateStructured", () -> fallback.generateStructured(prompt, responseSchemaJson));
    }

    @Override
    public String generateText(String prompt, byte[] image, String mimeType) {
        String r = primary.isConfigured() ? primary.generateText(prompt, image, mimeType) : null;
        if (r != null) return r;
        return novaFallback("generateText(image)", () -> fallback.generateText(prompt, image, mimeType));
    }

    /** Gemini 가 null 을 준 뒤 Nova 로 넘기는 공통 처리(폴백 발생을 로그로 남긴다). */
    private String novaFallback(String op, java.util.function.Supplier<String> novaCall) {
        if (!fallback.isConfigured()) return null;
        log.debug("Gemini {} 실패/미설정 → Nova 폴백", op);
        return novaCall.get();
    }
}
