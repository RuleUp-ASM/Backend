package com.ruleup.ruleup_backend.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** SafeSearch 응답 → 심사 결과. 판정을 못 내리면 UNAVAILABLE 이어야 호출부가 LLM 으로 넘긴다. */
class SafeSearchClientTest {

    private static final byte[] IMAGE = {1, 2, 3};
    private static final String URL = "https://vision.googleapis.com/v1/images:annotate?key=test-key";

    private static ModerationResult checkWith(String json) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        ModerationResult result = new SafeSearchClient("test-key", builder.build()).check(IMAGE);
        server.verify();
        return result;
    }

    private static String annotation(String adult, String violence, String racy) {
        return """
            {"responses":[{"safeSearchAnnotation":{"adult":"%s","spoof":"UNLIKELY","medical":"UNLIKELY","violence":"%s","racy":"%s"}}]}
            """.formatted(adult, violence, racy);
    }

    @Test
    @DisplayName("문제없는 이미지는 통과")
    void cleanImageIsApproved() {
        assertThat(checkWith(annotation("VERY_UNLIKELY", "UNLIKELY", "POSSIBLE"))).isEqualTo(ModerationResult.APPROVED);
    }

    @Test
    @DisplayName("음란·폭력이 LIKELY 이상이면 거부")
    void adultOrViolenceLikelyIsRejected() {
        assertThat(checkWith(annotation("LIKELY", "UNLIKELY", "UNLIKELY"))).isEqualTo(ModerationResult.REJECTED);
        assertThat(checkWith(annotation("UNLIKELY", "VERY_LIKELY", "UNLIKELY"))).isEqualTo(ModerationResult.REJECTED);
    }

    @Test
    @DisplayName("선정성은 VERY_LIKELY 만 거부 — LIKELY 는 운동·수영 사진 오탐이 많아 통과")
    void racyOnlyVeryLikelyIsRejected() {
        assertThat(checkWith(annotation("UNLIKELY", "UNLIKELY", "LIKELY"))).isEqualTo(ModerationResult.APPROVED);
        assertThat(checkWith(annotation("UNLIKELY", "UNLIKELY", "VERY_LIKELY"))).isEqualTo(ModerationResult.REJECTED);
    }

    @Test
    @DisplayName("응답 안의 오류·주석 없음은 판정 불가 — LLM 으로 넘긴다")
    void errorOrMissingAnnotationIsUnavailable() {
        assertThat(checkWith("{\"responses\":[{\"error\":{\"code\":3,\"message\":\"Bad image data.\"}}]}"))
                .isEqualTo(ModerationResult.UNAVAILABLE);
        assertThat(checkWith("{\"responses\":[{}]}")).isEqualTo(ModerationResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("HTTP 오류(키 거부·할당량 초과)는 판정 불가")
    void httpErrorIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(new SafeSearchClient("test-key", builder.build()).check(IMAGE)).isEqualTo(ModerationResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 판정 불가")
    void notConfiguredIsUnavailableWithoutCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        assertThat(new SafeSearchClient("", builder.build()).check(IMAGE)).isEqualTo(ModerationResult.UNAVAILABLE);
        server.verify();   // 요청이 하나도 없어야 한다
    }
}
