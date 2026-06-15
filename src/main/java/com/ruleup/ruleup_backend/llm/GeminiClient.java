package com.ruleup.ruleup_backend.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * Google Gemini(generateContent) 공용 호출기.
 * 루틴 매칭 / 챌린지 설정 추천 / 콘텐츠 검수가 모두 이 클라이언트를 통해 Gemini를 부른다.
 *
 * - JSON 강제: generationConfig.responseMimeType=application/json
 * - 멀티모달: 이미지 바이트를 inlineData로 함께 보낼 수 있어 사진 검수도 가능
 * - 실패/미설정은 예외 없이 null 반환 → 호출 측이 각자 폴백(none/empty/UNAVAILABLE)
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Gemini config;
    private final String model;

    public GeminiClient(AppProperties props) {
        AppProperties.Llm llm = (props != null) ? props.llm() : null;
        this.config = (llm != null) ? llm.gemini() : null;

        long timeoutMs = (config != null && config.timeoutMs() > 0) ? config.timeoutMs() : DEFAULT_TIMEOUT_MS;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        String baseUrl = (config != null && config.baseUrl() != null && !config.baseUrl().isBlank())
                ? config.baseUrl() : DEFAULT_BASE_URL;
        this.model = (config != null && config.model() != null && !config.model().isBlank())
                ? config.model() : DEFAULT_MODEL;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** API 키가 설정되어 호출 가능한 상태인지. */
    public boolean isConfigured() {
        return config != null && config.apiKey() != null && !config.apiKey().isBlank();
    }

    /** 텍스트 프롬프트 → 모델이 돌려준 텍스트(보통 JSON 문자열). 실패/미설정이면 null. */
    public String generateText(String prompt) {
        return generate(GeminiRequest.text(prompt));
    }

    /** 텍스트 + 이미지(멀티모달) → 모델 텍스트. 실패/미설정이면 null. */
    public String generateText(String prompt, byte[] image, String mimeType) {
        if (image == null || image.length == 0) return generateText(prompt);
        return generate(GeminiRequest.textAndImage(prompt, image, mimeType));
    }

    private String generate(GeminiRequest body) {
        if (!isConfigured()) {
            log.warn("Gemini API 키가 없어 호출을 건너뜁니다.");
            return null;
        }
        try {
            String raw = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) return null;

            GeminiResponse res = jsonMapper.readValue(raw, GeminiResponse.class);
            String content = (res != null) ? res.firstText() : null;
            return (content == null || content.isBlank()) ? null : content;

        } catch (Exception e) {
            // 타임아웃·HTTP·파싱 실패 전부 null → 호출 측이 폴백한다.
            log.warn("Gemini 호출 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 응답 텍스트에서 JSON 객체 부분만 추출(혹시 펜스/설명이 섞여 와도 대비). */
    public String extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /** 호출 측에서 모델 텍스트를 원하는 타입으로 파싱할 때 사용. 실패 시 null. */
    public <T> T parseJson(String content, Class<T> type) {
        try {
            return jsonMapper.readValue(extractJson(content), type);
        } catch (Exception e) {
            log.warn("Gemini 응답 JSON 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    // ===== Gemini generateContent 요청/응답 매핑 =====
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GeminiRequest(List<Content> contents,
                         @JsonProperty("generationConfig") GenerationConfig generationConfig) {

        static GeminiRequest text(String prompt) {
            return new GeminiRequest(
                    List.of(new Content("user", List.of(Part.text(prompt)))),
                    GenerationConfig.json());
        }

        static GeminiRequest textAndImage(String prompt, byte[] image, String mimeType) {
            String b64 = java.util.Base64.getEncoder().encodeToString(image);
            return new GeminiRequest(
                    List.of(new Content("user", List.of(Part.text(prompt), Part.image(mimeType, b64)))),
                    GenerationConfig.json());
        }

        record Content(String role, List<Part> parts) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Part(String text, @JsonProperty("inlineData") InlineData inlineData) {
            static Part text(String text) { return new Part(text, null); }
            static Part image(String mimeType, String base64Data) {
                return new Part(null, new InlineData(mimeType, base64Data));
            }
        }

        record InlineData(@JsonProperty("mimeType") String mimeType,
                          @JsonProperty("data") String data) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record GenerationConfig(Double temperature,
                                @JsonProperty("responseMimeType") String responseMimeType) {
            static GenerationConfig json() { return new GenerationConfig(0.0, "application/json"); }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Candidate(Content content) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Content(List<Part> parts) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Part(String text) {}

        String firstText() {
            if (candidates == null || candidates.isEmpty()) return null;
            Candidate c = candidates.get(0);
            if (c == null || c.content() == null || c.content().parts() == null
                    || c.content().parts().isEmpty()) return null;
            return c.content().parts().get(0).text();
        }
    }
}
