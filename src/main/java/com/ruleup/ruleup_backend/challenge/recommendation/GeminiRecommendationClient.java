package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Gemini로 챌린지 기본값 초안을 받아오는 클라이언트 (스펙 3.1 / 2.2 / 2.3).
 *  - 동기 호출 + 타임아웃(app.llm.gemini.timeout-ms). 트랜잭션 밖에서 호출되어야 함(서비스가 보장).
 *  - responseMimeType=application/json 으로 JSON만 받도록 강제.
 *  - 타임아웃/네트워크/파싱 실패는 전부 AI_RECOMMENDATION_FAILED(503)로 변환 → 클라가 직접 입력 모드로.
 *  - 받아온 값의 enum/숫자 "검증·보정"은 여기서 하지 않는다(서비스 책임).
 */
// 당분간 Solar 사용 → Gemini 비활성화. Gemini로 복귀 시 아래 @Component 주석을 해제하고
// SolarRecommendationClient의 @Component를 주석 처리하세요. (빈은 둘 중 하나만 등록)
// @Component
public class GeminiRecommendationClient implements RecommendationClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiRecommendationClient.class);
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AppProperties.Llm.Gemini config;

    public GeminiRecommendationClient(AppProperties props, ObjectMapper objectMapper) {
        this.config = props.llm().gemini();
        this.objectMapper = objectMapper;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(config.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(config.timeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** title(+description)로 추천 초안을 받아온다. 실패 시 BusinessException(AI_RECOMMENDATION_FAILED). */
    @Override
    public GeminiSuggestion recommend(String title, String description) {
        String uri = String.format(ENDPOINT, config.model());
        GeminiRequest body = GeminiRequest.of(buildPrompt(title, description));
        try {
            GeminiResponse res = restClient.post().uri(uri)
                    .header("x-goog-api-key", config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);

            String json = res != null ? res.firstText() : null;
            if (json == null || json.isBlank()) {
                log.warn("Gemini returned empty content");
                throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
            }
            return objectMapper.readValue(json, GeminiSuggestion.class);

        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {        // 타임아웃·연결 실패·HTTP 에러
            log.warn("Gemini call failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
        } catch (Exception e) {                   // JSON 파싱 실패 등
            log.warn("Gemini response parse failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
        }
    }

    /**
     * 프롬프트. enum 후보를 명시해 LLM이 우리 코드 체계 안에서만 고르도록 유도한다.
     * (그래도 틀릴 수 있으므로 서버에서 재검증한다 — 신뢰 경계)
     */
    private String buildPrompt(String title, String description) {
        return """
            너는 습관 챌린지 앱의 추천 도우미다. 사용자가 입력한 챌린지 제목과 설명을 보고
            합리적인 기본값을 추천해라. 반드시 아래 JSON 스키마로만 응답해라(설명/마크다운 금지).

            제목: %s
            설명: %s

            JSON 필드:
            - refinedTitle: 다듬은 제목(30자 이내)
            - description: 한 줄 설명(200자 이내, 없으면 빈 문자열)
            - category: 다음 중 하나 [EXERCISE, READING, MEDITATION, HEALTH, WAKE_UP, WORK, STUDY,
              HOBBY, COOKING, FINANCE, ENVIRONMENT, RELATIONSHIP, MUSIC, WRITING, CODING]
            - participationType: SOLO 또는 GROUP
            - minMannerTemperature: participationType이 GROUP이면 0~100 사이 숫자(예 65.0), SOLO이면 null
            - repeatDays: [MON, TUE, WED, THU, FRI, SAT, SUN] 중 1개 이상의 배열
            - durationDays: 1 이상의 정수(일), 예 14
            - verificationMethods: [GPS, PHOTO, SCREEN_TIME, SELF_CHECK] 중 1개 이상의 배열
            - mannerDeduction: 실패 시 매너 차감 숫자(예 0.5)
            - snsShare: 불리언
            - groupShare: 불리언
            - mannerGain: 성공 시 매너 가산 숫자(예 0.6)
            """.formatted(title, description == null ? "" : description);
    }

    // ===== Gemini 요청/응답 매핑 (필요한 필드만) =====
    record GeminiRequest(List<Content> contents,
                         @JsonProperty("generationConfig") GenerationConfig generationConfig) {
        static GeminiRequest of(String prompt) {
            return new GeminiRequest(
                    List.of(new Content(List.of(new Part(prompt)))),
                    new GenerationConfig("application/json", 0.4));
        }
        record Content(List<Part> parts) {}
        record Part(String text) {}
        record GenerationConfig(@JsonProperty("responseMimeType") String responseMimeType,
                                Double temperature) {}
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record GeminiResponse(List<Candidate> candidates) {
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        record Candidate(Content content) {}
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        record Content(List<Part> parts) {}
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        record Part(String text) {}

        String firstText() {
            if (candidates == null || candidates.isEmpty()) return null;
            var content = candidates.get(0).content();
            if (content == null || content.parts() == null || content.parts().isEmpty()) return null;
            return content.parts().get(0).text();
        }
    }
}