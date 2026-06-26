package com.ruleup.ruleup_backend.places;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.places.dto.PlaceSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 장소 검색 프록시(§5.2). 클라가 키워드로 브랜드/매장을 찾아 앵커 좌표를 잡는 데 쓴다.
 * Naver Local Search(openapi.naver.com/v1/search/local.json)를 서버가 중계 — 클라에 키 노출 안 함.
 *
 * <p>설계 메모:
 *  - 자격증명 미설정이면 호출 불가 → 빈 목록(GeminiClient와 같은 "키 없으면 graceful" 철학).
 *    실제 배포 전 NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 주입 필요(아래 @Value).
 *  - mapx/mapy = 경도·위도 × 1e7(WGS84). lat=mapy/1e7, lng=mapx/1e7로 환산.
 *  - 레이트리밋(분당 호출 제한)은 컨트롤러/게이트웨이 레벨에서 처리 권장(PLACE_SEARCH_RATE_LIMIT).
 *
 * <p>FLAG: Naver API 키 발급·주입 필요. 키워드 정규화·페이지네이션·캐시는 후속.
 */
@Service
public class PlaceSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);
    private static final String LOCAL_PATH = "/v1/search/local.json";
    private static final int DEFAULT_DISPLAY = 5;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String clientId;
    private final String clientSecret;

    public PlaceSearchService(
            @Value("${app.places.naver.base-url:https://openapi.naver.com}") String baseUrl,
            @Value("${app.places.naver.client-id:}") String clientId,
            @Value("${app.places.naver.client-secret:}") String clientSecret,
            @Value("${app.places.naver.timeout-ms:3000}") long timeoutMs) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /** 키워드로 장소 검색. lat/lng는 정렬 힌트(선택). 미설정·실패 시 빈 목록. */
    public PlaceSearchResponse search(String query, Double lat, Double lng) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_QUERY);
        }
        if (!isConfigured()) {
            log.warn("PlaceSearch: Naver 자격증명 미설정 — 빈 결과 반환");
            return new PlaceSearchResponse(List.of());
        }
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path(LOCAL_PATH)
                            .queryParam("query", query)
                            .queryParam("display", DEFAULT_DISPLAY)
                            .queryParam("sort", "random")
                            .build())
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.warn("PlaceSearch 호출 실패: {}", e.toString());
            return new PlaceSearchResponse(List.of());
        }
    }

    private PlaceSearchResponse parse(String body) {
        List<PlaceSearchResponse.Item> items = new ArrayList<>();
        if (body == null || body.isBlank()) return new PlaceSearchResponse(items);
        try {
            NaverLocal parsed = jsonMapper.readValue(body, NaverLocal.class);
            if (parsed != null && parsed.items() != null) {
                for (NaverItem n : parsed.items()) {
                    items.add(new PlaceSearchResponse.Item(
                            stripTags(n.title()),
                            n.address() == null ? "" : n.address(),
                            n.roadAddress() == null ? "" : n.roadAddress(),
                            n.category() == null ? "" : n.category(),
                            parseCoord(n.mapy()),    // 위도
                            parseCoord(n.mapx())));   // 경도
                }
            }
        } catch (Exception e) {
            log.warn("PlaceSearch 파싱 실패: {}", e.toString());
        }
        return new PlaceSearchResponse(items);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NaverLocal(List<NaverItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NaverItem(String title, String address, String roadAddress, String category,
                     String mapx, String mapy) {}

    /** "&lt;b&gt;스타벅스&lt;/b&gt; 강남" → "스타벅스 강남" */
    private String stripTags(String s) {
        return (s == null) ? "" : s.replaceAll("<[^>]+>", "");
    }

    /** Naver mapx/mapy(경위도×1e7 정수 문자열) → WGS84 도(degree). */
    private double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) return 0d;
        try {
            return Long.parseLong(raw.trim()) / 1e7;
        } catch (NumberFormatException e) {
            try { return Double.parseDouble(raw.trim()); } catch (NumberFormatException ignored) { return 0d; }
        }
    }
}
