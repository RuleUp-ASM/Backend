package com.ruleup.ruleup_backend.moderation;

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
 * Solar(Upstage)로 닉네임을 검수하는 클라이언트.
 * 기존 SolarChallengeSettingsClient와 동일 방식(OpenAI 호환 chat completions, JSON 파싱).
 * 어떤 실패든 {@link ModerationResult#UNAVAILABLE}로 폴백 → 검수 보류(가입은 이미 끝났으므로 안전).
 *
 * ⚠️ 이미지 검수는 Solar(텍스트 전용)에서 불가 → 항상 UNAVAILABLE. 추후 Gemini 멀티모달로 교체.
 */
@Component
public class SolarModerationClient implements ContentModerationClient {

    private static final Logger log = LoggerFactory.getLogger(SolarModerationClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.upstage.ai/v1";
    private static final String DEFAULT_MODEL = "solar-mini";
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AppProperties.Llm.Solar config;

    public SolarModerationClient(AppProperties props) {
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
    public ModerationResult moderateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return ModerationResult.APPROVED;
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("Solar API 키가 없어 닉네임 검수를 보류합니다(PENDING 유지).");
            return ModerationResult.UNAVAILABLE;
        }
        String model = (config.model() != null && !config.model().isBlank()) ? config.model() : DEFAULT_MODEL;
        SolarRequest body = SolarRequest.of(model, buildNicknamePrompt(nickname));
        try {
            String raw = restClient.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) return ModerationResult.UNAVAILABLE;

            SolarResponse res = jsonMapper.readValue(raw, SolarResponse.class);
            String content = (res != null) ? res.firstContent() : null;
            if (content == null || content.isBlank()) return ModerationResult.UNAVAILABLE;

            Verdict verdict = jsonMapper.readValue(extractJson(content), Verdict.class);
            if (verdict == null) return ModerationResult.UNAVAILABLE;
            return verdict.flagged() ? ModerationResult.REJECTED : ModerationResult.APPROVED;

        } catch (Exception e) {
            // AI가 막히면 인증을 미룬다 = 보류(PENDING). 절대 가입/사용을 막지 않는다.
            log.warn("Solar 닉네임 검수 실패(보류): {}", e.getMessage());
            return ModerationResult.UNAVAILABLE;
        }
    }

    @Override
    public ModerationResult moderateImage(String imageUrl) {
        // Solar는 텍스트 전용 → 이미지 검수 불가. 보류 처리(타인에게는 숨김 유지).
        // 추후 Gemini 멀티모달 클라이언트로 교체 시 실제 검수.
        if (imageUrl != null && !imageUrl.isBlank()) {
            log.info("이미지 검수는 현재 모델(Solar)에서 미지원 → 보류. url={}", imageUrl);
        }
        return ModerationResult.UNAVAILABLE;
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private String buildNicknamePrompt(String nickname) {
        return """
            너는 커뮤니티 닉네임 검수자다. 아래 닉네임이 다른 사용자에게 노출되어도 되는지 판단하라.
            다음 중 하나라도 해당하면 문제 있음(flagged=true)이다:
            - 정치적 선동/특정 정당·정치인 비방
            - 음란/성적 표현
            - 욕설/혐오/비난/차별
            - 타인 사칭, 광고/스팸

            애매하면 통과(flagged=false)시키되, 명백히 문제면 막아라.
            반드시 JSON으로만 답하라(설명 금지).

            닉네임: "%s"

            출력 JSON 예: {"flagged": false, "reason": ""}
            또는: {"flagged": true, "reason": "욕설 포함"}
            """.formatted(nickname);
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
            return new SolarRequest(model, List.of(new Message("user", prompt)), 0.0, rf);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Verdict(boolean flagged, String reason) {}
}
