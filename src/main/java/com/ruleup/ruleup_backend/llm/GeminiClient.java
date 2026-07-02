package com.ruleup.ruleup_backend.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Google Gemini(generateContent) 공용 호출기.
 * 루틴 매칭 / 챌린지 설정 추천 / 콘텐츠 검수가 모두 이 클라이언트를 통해 Gemini를 부른다.
 *
 * - JSON 강제: generationConfig.responseMimeType=application/json (+ 필요 시 responseSchema 로 타입·enum 강제)
 * - 멀티모달: 이미지 바이트를 inlineData로 함께 보낼 수 있어 사진 검수도 가능
 * - 실패/미설정은 예외 없이 null 반환 → 호출 측이 각자 폴백(none/empty/UNAVAILABLE)
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final int DEFAULT_MAX_RETRIES = 2;          // 최초 1회 + 재시도 2회 = 최대 3번
    private static final long DEFAULT_RETRY_BACKOFF_MS = 300L; // 지수 백오프 base
    /** 일시 오류로 보고 재시도할 HTTP 상태(503 UNAVAILABLE·429 Too Many·5xx 일부). */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Gemini config;
    private final String model;
    private final int maxRetries;
    private final long retryBackoffMs;

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
        this.maxRetries = (config != null && config.maxRetries() > 0)
                ? config.maxRetries() : DEFAULT_MAX_RETRIES;
        this.retryBackoffMs = (config != null && config.retryBackoffMs() > 0)
                ? config.retryBackoffMs() : DEFAULT_RETRY_BACKOFF_MS;
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

    /**
     * 텍스트 프롬프트 + 응답 스키마 강제 → 모델 텍스트. 실패/미설정이면 null.
     * responseSchema 는 Gemini 의 OpenAPI 서브셋(대문자 타입: STRING/OBJECT/INTEGER/ARRAY/BOOLEAN)
     * JSON 문자열로 준다. enum·타입·필수 필드를 프롬프트가 아니라 API 레벨에서 강제한다.
     * 스키마가 null/파싱불가면 스키마 없이(기존과 동일하게) 진행한다.
     */
    public String generateStructured(String prompt, String responseSchemaJson) {
        return generate(GeminiRequest.text(prompt, parseSchema(responseSchemaJson)));
    }

    /** responseSchema JSON 문자열 → 요청 바디에 실을 객체 트리. 실패 시 null(스키마 미적용). */
    private Object parseSchema(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return jsonMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("responseSchema 파싱 실패 — 스키마 없이 진행: {}", e.getMessage());
            return null;
        }
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
        // 503(UNAVAILABLE)·429·타임아웃 같은 일시 오류는 짧은 백오프 후 재시도. 그 외/소진 시 null 폴백.
        int attempts = maxRetries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                // 응답을 byte[] 로 받는다: Gemini 가 application/octet-stream 으로 줘도 변환 실패하지 않음.
                // onStatus no-op 으로 4xx/5xx 에 예외를 던지지 않게 해 상태코드를 직접 보고 재시도 판단.
                ResponseEntity<byte[]> resp = restClient.post()
                        .uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", config.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> { })
                        .toEntity(byte[].class);

                HttpStatusCode status = resp.getStatusCode();
                if (status.is2xxSuccessful()) {
                    byte[] bytes = resp.getBody();
                    if (bytes == null || bytes.length == 0) return null;
                    GeminiResponse res = jsonMapper.readValue(
                            new String(bytes, StandardCharsets.UTF_8), GeminiResponse.class);
                    String content = (res != null) ? res.firstText() : null;
                    return (content == null || content.isBlank()) ? null : content;
                }

                // 에러 상태: 재시도 대상이고 기회가 남았으면 백오프 후 재시도, 아니면 폴백.
                if (RETRYABLE_STATUS.contains(status.value()) && attempt < attempts) {
                    log.warn("Gemini 호출 실패(status={}, {}/{}회) — 재시도", status.value(), attempt, attempts);
                    if (!backoff(attempt)) return null;
                    continue;
                }
                log.warn("Gemini 호출 실패: status={} body={}", status.value(), errorBody(resp.getBody()));
                return null;

            } catch (ResourceAccessException timeout) {
                // 연결/읽기 타임아웃: 재시도 가치 있음.
                if (attempt < attempts) {
                    log.warn("Gemini 호출 타임아웃({}/{}회) — 재시도: {}", attempt, attempts, timeout.getMessage());
                    if (!backoff(attempt)) return null;
                    continue;
                }
                log.warn("Gemini 호출 실패(타임아웃): {}", timeout.getMessage());
                return null;
            } catch (Exception e) {
                // 직렬화·파싱 등 재시도해도 같을 오류는 즉시 폴백.
                log.warn("Gemini 호출 실패: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** 지수 백오프 대기. 인터럽트되면 false(중단 신호) → 호출 측이 즉시 폴백. */
    private boolean backoff(int attempt) {
        long wait = retryBackoffMs * (1L << (attempt - 1)); // 300, 600, 1200ms ...
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 에러 응답 바이트를 로그용 문자열로(앞부분만). */
    private static String errorBody(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        String s = new String(bytes, StandardCharsets.UTF_8);
        return (s.length() > 300) ? s.substring(0, 300) : s;
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

        static GeminiRequest text(String prompt, Object responseSchema) {
            return new GeminiRequest(
                    List.of(new Content("user", List.of(Part.text(prompt)))),
                    (responseSchema == null) ? GenerationConfig.json()
                            : GenerationConfig.json(responseSchema));
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
                                @JsonProperty("responseMimeType") String responseMimeType,
                                @JsonProperty("responseSchema") Object responseSchema) {
            static GenerationConfig json() { return new GenerationConfig(0.0, "application/json", null); }
            static GenerationConfig json(Object schema) { return new GenerationConfig(0.0, "application/json", schema); }
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
