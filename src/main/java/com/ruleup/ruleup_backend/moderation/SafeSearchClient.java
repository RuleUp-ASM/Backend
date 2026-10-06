package com.ruleup.ruleup_backend.moderation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 이미지 심사 1순위 — Google Cloud Vision SafeSearch.
 *
 * <p>LLM 보다 빠르고 싸며 판정이 결정적이다. 다만 SafeSearch 는 음란·폭력·선정성만 본다 — 정치 선동·광고·사칭은
 * 모른다. 그래도 SafeSearch 가 판정을 내리면 그대로 쓰고, <b>판정을 못 내렸을 때만</b>(키 미설정·오류·타임아웃)
 * 호출부가 LLM(Gemini → Nova)으로 넘긴다.
 *
 * <p>기준(가능성 등급 VERY_UNLIKELY < UNLIKELY < POSSIBLE < LIKELY < VERY_LIKELY):
 * <ul>
 *   <li>adult·violence 가 LIKELY 이상 → 거부</li>
 *   <li>racy(선정성)는 VERY_LIKELY 만 거부 — 운동·수영 사진이 흔한 앱이라 LIKELY 는 오탐이 많다</li>
 *   <li>medical·spoof 는 보지 않는다</li>
 * </ul>
 * 어떤 실패든 예외 대신 {@link ModerationResult#UNAVAILABLE} 이다.
 */
@Component
public class SafeSearchClient {

    private static final Logger log = LoggerFactory.getLogger(SafeSearchClient.class);
    private static final String ENDPOINT = "https://vision.googleapis.com/v1/images:annotate";
    private static final List<String> LEVELS = List.of("VERY_UNLIKELY", "UNLIKELY", "POSSIBLE", "LIKELY", "VERY_LIKELY");

    private final String apiKey;
    private final RestClient http;

    @Autowired
    public SafeSearchClient(@Value("${app.moderation.safe-search.api-key:}") String apiKey) {
        this(apiKey, defaultClient());
    }

    SafeSearchClient(String apiKey, RestClient http) {
        this.apiKey = apiKey;
        this.http = http;
        log.info("SafeSearch configured={}", isConfigured());
    }

    private static RestClient defaultClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public ModerationResult check(byte[] image) {
        if (!isConfigured() || image == null || image.length == 0) return ModerationResult.UNAVAILABLE;
        try {
            Map<String, Object> body = Map.of("requests", List.of(Map.of(
                    "image", Map.of("content", Base64.getEncoder().encodeToString(image)),
                    "features", List.of(Map.of("type", "SAFE_SEARCH_DETECTION")))));
            Response res = http.post().uri(ENDPOINT + "?key={key}", apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Response.class);
            if (res == null || res.responses() == null || res.responses().isEmpty()) return unavailable("빈 응답");
            AnnotateResult r = res.responses().getFirst();
            if (r.error() != null) return unavailable("error=" + r.error().message());
            Annotation a = r.safeSearchAnnotation();
            if (a == null) return unavailable("safeSearchAnnotation 없음");
            boolean reject = atLeast(a.adult(), "LIKELY") || atLeast(a.violence(), "LIKELY") || atLeast(a.racy(), "VERY_LIKELY");
            return reject ? ModerationResult.REJECTED : ModerationResult.APPROVED;
        } catch (Exception e) {
            // 키·요청 내용이 메시지에 섞일 수 있어 예외 종류만 남긴다.
            return unavailable(e.getClass().getSimpleName());
        }
    }

    private static boolean atLeast(String level, String threshold) {
        return level != null && LEVELS.indexOf(level) >= LEVELS.indexOf(threshold);
    }

    private static ModerationResult unavailable(String why) {
        log.warn("safesearch_unavailable reason={} — LLM 심사로 넘긴다", why);
        return ModerationResult.UNAVAILABLE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<AnnotateResult> responses) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnnotateResult(Annotation safeSearchAnnotation, Status error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Annotation(String adult, String violence, String racy, String medical, String spoof) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Status(Integer code, String message) {}
}
