package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;
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
 * Solar(Upstage)로 챌린지 기본값 초안을 받아오는 클라이언트 (스펙 3.1 / 2.2 / 2.3).
 *
 * ※ 당분간 Solar를 사용하기 위한 임시 구현. 나중에 GeminiRecommendationClient로 복귀 예정.
 *   전환: 이 클래스의 @Component를 주석 처리하고, GeminiRecommendationClient의 @Component 주석을 해제.
 *
 * Solar는 OpenAI 호환 chat completions API.
 *  - POST {base-url}/chat/completions, 헤더 Authorization: Bearer {api-key}
 *  - response_format=json_object 는 solar-pro2 모델에서만 지원되며, JSON 모드 사용 시
 *    프롬프트에 "JSON" 단어가 포함돼야 함(프롬프트에 명시되어 있음).
 *  - 타임아웃/네트워크/파싱 실패는 전부 AI_RECOMMENDATION_FAILED(503)로 변환.
 *  - 받아온 값의 enum/숫자 검증·보정은 여기서 하지 않는다(서비스 책임, 신뢰 경계).
 */
@Component
public class SolarRecommendationClient implements RecommendationClient {

    private static final Logger log = LoggerFactory.getLogger(SolarRecommendationClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.upstage.ai/v1";
    private static final String DEFAULT_MODEL = "solar-mini";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RestClient restClient;
    // Spring Boot 4는 Jackson 3(tools.jackson)이라 com.fasterxml...ObjectMapper 빈이 없음 → 직접 생성.
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Solar config;   // 설정 누락 시 null일 수 있음(앱 기동은 막지 않음)

    public SolarRecommendationClient(AppProperties props) {
        AppProperties.Llm llm = (props != null) ? props.llm() : null;
        this.config = (llm != null) ? llm.solar() : null;
        if (config == null)
            log.warn("app.llm.solar 설정이 없습니다. 추천 API(3.1)는 동작하지 않습니다(나머지 기능은 정상).");

        long timeoutMs = (config != null && config.timeoutMs() > 0) ? config.timeoutMs() : DEFAULT_TIMEOUT_MS;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        // base-url 예: https://api.upstage.ai/v1  → 실제 호출은 {base-url}/chat/completions
        String baseUrl = (config != null && config.baseUrl() != null && !config.baseUrl().isBlank())
                ? config.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public GeminiSuggestion recommend(String title, String description) {
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("Solar 설정/API 키가 없어 추천을 수행할 수 없습니다.");
            throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
        }
        String model = (config.model() != null && !config.model().isBlank()) ? config.model() : DEFAULT_MODEL;
        SolarRequest body = SolarRequest.of(model, buildPrompt(title, description));
        try {
            // 응답 Content-Type이 application/json이 아닐 수 있어(octet-stream 등) String으로 받아 직접 파싱.
            String raw = restClient.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                log.warn("Solar returned empty body");
                throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
            }
            SolarResponse res = jsonMapper.readValue(raw, SolarResponse.class);
            String content = (res != null) ? res.firstContent() : null;
            if (content == null || content.isBlank()) {
                log.warn("Solar returned empty content");
                throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
            }
            // 모델이 ```json 펜스나 잡설을 섞을 수 있어 첫 '{' ~ 마지막 '}'만 추출 후 파싱.
            return jsonMapper.readValue(extractJson(content), GeminiSuggestion.class);

        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {        // 타임아웃·연결 실패·HTTP 에러
            log.warn("Solar call failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
        } catch (Exception e) {                   // JSON 파싱 실패 등
            log.warn("Solar response parse failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_RECOMMENDATION_FAILED);
        }
    }

    /** 응답 본문에서 JSON 객체 부분만 안전하게 추출(펜스/설명 섞임 대비). */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /**
     * 프롬프트. enum 후보를 명시해 LLM이 우리 코드 체계 안에서만 고르도록 유도한다.
     * (그래도 틀릴 수 있으므로 서버에서 재검증한다 — 신뢰 경계)
     * JSON 모드 요구사항대로 "JSON"이라는 단어를 포함한다.
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

    // ===== Solar(OpenAI 호환) 요청/응답 매핑 (필요한 필드만) =====
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record SolarRequest(String model,
                        List<Message> messages,
                        Double temperature,
                        @JsonProperty("response_format") ResponseFormat responseFormat) {
        static SolarRequest of(String model, String prompt) {
            // response_format(json_object)는 solar-pro2 계열만 지원. 그 외(solar-mini 등)는 보내지 않는다.
            // (프롬프트가 "JSON으로만 응답"을 요구하고, 응답에서 {...}만 추출해 파싱하므로 동작에 지장 없음)
            boolean jsonMode = model != null && model.toLowerCase().contains("pro2");
            ResponseFormat rf = jsonMode ? new ResponseFormat("json_object") : null;  // null이면 위 @JsonInclude로 생략됨
            return new SolarRequest(
                    model,
                    List.of(new Message("user", prompt)),
                    0.4,
                    rf);
        }
        record Message(String role, String content) {}
        record ResponseFormat(String type) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SolarResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Message(String content) {}

        String firstContent() {
            if (choices == null || choices.isEmpty()) return null;
            var msg = choices.get(0).message();
            return (msg != null) ? msg.content() : null;
        }
    }
}