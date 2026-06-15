package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;
import com.ruleup.ruleup_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Solar(Upstage)로 챌린지 기본 설정을 제안받는 클라이언트. 기존 SolarRoutineMatchClient 와 동일 방식
 * (OpenAI 호환 chat completions, JSON 만 받아 파싱). 실패는 전부 ChallengeSettings.empty() 로 폴백.
 */
@Component
public class SolarChallengeSettingsClient implements ChallengeSettingsClient {

    private static final Logger log = LoggerFactory.getLogger(SolarChallengeSettingsClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.upstage.ai/v1";
    private static final String DEFAULT_MODEL = "solar-mini";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Solar config;

    public SolarChallengeSettingsClient(AppProperties props) {
        AppProperties.Llm llm = (props != null) ? props.llm() : null;
        this.config = (llm != null) ? llm.solar() : null;

        long timeoutMs = (config != null && config.timeoutMs() > 0) ? config.timeoutMs() : DEFAULT_TIMEOUT_MS;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        String baseUrl = (config != null && config.baseUrl() != null && !config.baseUrl().isBlank())
                ? config.baseUrl() : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public ChallengeSettings suggest(String title, String description) {
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("Solar 설정/API 키가 없어 챌린지 설정 추천을 건너뜁니다(기본값 폴백).");
            return ChallengeSettings.empty();
        }
        String model = (config.model() != null && !config.model().isBlank()) ? config.model() : DEFAULT_MODEL;
        SolarRequest body = SolarRequest.of(model, buildPrompt(title, description));
        try {
            String raw = restClient.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) return ChallengeSettings.empty();

            SolarResponse res = jsonMapper.readValue(raw, SolarResponse.class);
            String content = (res != null) ? res.firstContent() : null;
            if (content == null || content.isBlank()) return ChallengeSettings.empty();

            return jsonMapper.readValue(extractJson(content), ChallengeSettings.class);

        } catch (Exception e) {
            log.warn("Solar challenge settings failed: {}", e.getMessage());
            return ChallengeSettings.empty();
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private String buildPrompt(String title, String description) {
        return """
            너는 습관 챌린지 설정 도우미다. 사용자가 만들려는 챌린지 제목/설명을 보고
            합리적인 기본 설정을 제안하라. 반드시 JSON 으로만 답하라(설명 금지).

            제목: %s
            설명: %s

            출력 키:
            - participationType: "SOLO" 또는 "GROUP" (혼자 할 습관이면 SOLO, 같이 하는 게 자연스러우면 GROUP)
            - repeatDays: 요일 배열. ["MON","TUE","WED","THU","FRI","SAT","SUN"] 중 적절히. 매일이면 7개 전부.
            - durationDays: 챌린지 기간(일). 보통 7~30.
            - mannerDeduction: 실패 시 매너 차감(0 이상 숫자, 보통 0.5~3.0)
            - mannerGain: 성공 시 매너 가산(0 이상 숫자, 보통 0.5~3.0)
            - anonymity: "REAL" 또는 "ANONYMOUS"

            출력 JSON 예: {"participationType":"SOLO","repeatDays":["MON","TUE","WED","THU","FRI"],"durationDays":14,"mannerDeduction":1.0,"mannerGain":1.0,"anonymity":"REAL"}
            """.formatted(title, description == null ? "" : description);
    }

    // ===== Solar(OpenAI 호환) 요청/응답 매핑 =====
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SolarRequest(String model,
                        List<Message> messages,
                        Double temperature,
                        @JsonProperty("response_format") ResponseFormat responseFormat) {
        static SolarRequest of(String model, String prompt) {
            boolean jsonMode = model != null && model.toLowerCase().contains("pro2");
            ResponseFormat rf = jsonMode ? new ResponseFormat("json_object") : null;
            return new SolarRequest(model, List.of(new Message("user", prompt)), 0.3, rf);
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