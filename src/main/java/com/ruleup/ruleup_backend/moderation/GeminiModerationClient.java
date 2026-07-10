package com.ruleup.ruleup_backend.moderation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;

/**
 * Gemini(멀티모달)로 닉네임과 프로필 사진을 검수하는 클라이언트.
 *  - 닉네임: 텍스트 검수
 *  - 사진  : 이미지 바이트를 받아 멀티모달 검수 (Gemini라 실제로 가능)
 * 어떤 실패든 {@link ModerationResult#UNAVAILABLE}로 폴백 → 검수 보류(가입은 이미 끝났으므로 안전).
 */
@Component
public class GeminiModerationClient implements ContentModerationClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiModerationClient.class);
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;   // 프로필 사진 10MB 제한과 동일

    private final LlmClient gemini;
    private final RestClient imageFetcher;

    public GeminiModerationClient(LlmClient gemini) {
        this.gemini = gemini;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.imageFetcher = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ModerationResult moderateNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return ModerationResult.APPROVED;
        String content = gemini.generateText(buildNicknamePrompt(nickname));
        return toResult(content);
    }

    @Override
    public ModerationResult moderateImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return ModerationResult.APPROVED;
        byte[] bytes = fetchImage(imageUrl);
        if (bytes == null) return ModerationResult.UNAVAILABLE;   // 못 가져오면 보류
        return moderateImageBytes(bytes, guessMimeType(imageUrl));
    }

    @Override
    public ModerationResult moderateImageBytes(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) return ModerationResult.UNAVAILABLE;
        if (bytes.length > MAX_IMAGE_BYTES) {
            log.warn("이미지가 너무 커서 검수를 보류합니다(size={} bytes).", bytes.length);
            return ModerationResult.UNAVAILABLE;
        }
        String mime = (mimeType != null && !mimeType.isBlank()) ? mimeType : "image/jpeg";
        String content = gemini.generateText(buildImagePrompt(), bytes, mime);
        return toResult(content);
    }

    /** 모델 JSON({"flagged":bool}) → 검수 결과. 파싱 실패/응답 없음은 보류. */
    private ModerationResult toResult(String content) {
        if (content == null) return ModerationResult.UNAVAILABLE;
        Verdict verdict = gemini.parseJson(content, Verdict.class);
        if (verdict == null) return ModerationResult.UNAVAILABLE;
        return verdict.flagged() ? ModerationResult.REJECTED : ModerationResult.APPROVED;
    }

    private byte[] fetchImage(String imageUrl) {
        try {
            byte[] bytes = imageFetcher.get().uri(imageUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) return null;
            if (bytes.length > MAX_IMAGE_BYTES) {
                log.warn("프로필 사진이 너무 커서 검수를 보류합니다(size={} bytes).", bytes.length);
                return null;
            }
            return bytes;
        } catch (Exception e) {
            log.warn("프로필 사진 다운로드 실패(검수 보류): {}", e.getMessage());
            return null;
        }
    }

    private String guessMimeType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";   // jpg/jpeg 및 기본값
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

    private String buildImagePrompt() {
        return """
            너는 커뮤니티 프로필 사진 검수자다. 첨부된 이미지가 다른 사용자에게 노출되어도 되는지 판단하라.
            다음 중 하나라도 해당하면 문제 있음(flagged=true)이다:
            - 음란/성적/노출 이미지
            - 폭력/혐오/차별 상징
            - 정치적 선동
            - 광고/스팸, 타인 사칭

            애매하면 통과(flagged=false)시키되, 명백히 문제면 막아라.
            반드시 JSON으로만 답하라(설명 금지).

            출력 JSON 예: {"flagged": false, "reason": ""}
            또는: {"flagged": true, "reason": "노출 이미지"}
            """;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Verdict(boolean flagged, String reason) {}
}
