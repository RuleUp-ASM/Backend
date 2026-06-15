package com.ruleup.ruleup_backend.routine.match;

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
import java.util.stream.Collectors;

/**
 * Solar(Upstage)로 "제목 → 템플릿 매칭 + 목표값 추출"을 받아오는 클라이언트.
 *
 * 기존 SolarRecommendationClient 와 같은 방식(OpenAI 호환 chat completions, JSON만 받아 파싱).
 * 다만 LLM 의 책임이 작다: 후보 목록에서 templateId 하나 고르고 목표값(숫자/시간)만 뽑는다.
 * 인증 방식·권한 같은 값은 절대 LLM 이 만들지 않는다(서버 템플릿이 진실).
 */
@Component
public class SolarRoutineMatchClient implements RoutineMatchClient {

    private static final Logger log = LoggerFactory.getLogger(SolarRoutineMatchClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.upstage.ai/v1";
    private static final String DEFAULT_MODEL = "solar-mini";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Solar config;

    public SolarRoutineMatchClient(AppProperties props) {
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
    public RoutineMatch match(String title, String description, List<RoutineCandidate> candidates) {
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            // 설정 없음 → 매칭 불가. 서비스가 수동으로 폴백.
            log.warn("Solar 설정/API 키가 없어 루틴 매칭을 건너뜁니다(수동 폴백).");
            return RoutineMatch.none();
        }
        String model = (config.model() != null && !config.model().isBlank()) ? config.model() : DEFAULT_MODEL;
        SolarRequest body = SolarRequest.of(model, buildPrompt(title, description, candidates));
        try {
            String raw = restClient.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) return RoutineMatch.none();

            SolarResponse res = jsonMapper.readValue(raw, SolarResponse.class);
            String content = (res != null) ? res.firstContent() : null;
            if (content == null || content.isBlank()) return RoutineMatch.none();

            return jsonMapper.readValue(extractJson(content), RoutineMatch.class);

        } catch (Exception e) {
            // 타임아웃·HTTP·파싱 실패 전부 "매칭 실패"로 → 서비스가 수동 인증으로 폴백.
            log.warn("Solar routine match failed: {}", e.getMessage());
            return RoutineMatch.none();
        }
    }

    /** 응답 본문에서 JSON 객체 부분만 추출(펜스/설명 섞임 대비). */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /**
     * 프롬프트. 후보 목록(id·이름·목표값 키)을 주고 그 중 하나만 고르게 한다.
     * JSON 모드 요구사항대로 "JSON" 단어를 포함한다.
     */
    private String buildPrompt(String title, String description, List<RoutineCandidate> candidates) {
        String list = candidates.stream()
                .map(c -> "%d. %s%s".formatted(
                        c.id(), c.name(),
                        c.paramKeys().isEmpty() ? "" : " (목표값: " + String.join(", ", c.paramKeys()) + ")"))
                .collect(Collectors.joining("\n"));

        return """
            너는 습관 루틴 매칭기다. 사용자가 입력한 제목/설명을 아래 후보 루틴 중 의미가 가장 가까운
            하나에 매칭하고, 사용자가 말한 목표 수치가 있으면 뽑아라. 반드시 JSON 으로만 답하라(설명 금지).

            제목: %s
            설명: %s

            후보 루틴(id. 이름 (목표값 키)):
            %s

            규칙:
            - templateId: 위 후보 중 가장 가까운 루틴의 id(숫자). 적절한 게 없으면 null.
            - params: 사용자가 명시한 목표값만 채운다. 키는 해당 후보의 "목표값 키"만 사용.
              숫자는 숫자로(예: 5), 시간은 "HH:mm" 문자열로(예: "07:00"). 언급 없으면 비운다({}).
            - 추측해서 새 키를 만들지 말 것. 후보에 없는 id 를 쓰지 말 것.

            출력 JSON 예: {"templateId": 2, "params": {"distance_km": 5}}
            """.formatted(title, description == null ? "" : description, list);
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
            return new SolarRequest(model, List.of(new Message("user", prompt)), 0.2, rf);
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