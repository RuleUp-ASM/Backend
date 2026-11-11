package com.ruleup.ruleup_backend.observability.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InquirySlackAlertTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INQUIRY = "01a1c923-9bf2-7a24-b527-fad2d9620a8a";

    @Test
    @DisplayName("분류·사진·답변 대기 건수·KST 시각·콘솔 링크를 싣고, 본문은 싣지 않는다")
    void customNotificationShape() {
        String body = OpsSlackChannel.body(InquirySlackAlert.title("prod"),
                InquirySlackAlert.description("VERIFICATION", 2, 3L, Instant.parse("2026-11-11T05:45:16Z"), INQUIRY));

        JsonNode node = JSON.readTree(body);
        assertThat(node.at("/content/title").asString()).isEqualTo(":speech_balloon: [prod] 새 문의");
        assertThat(node.at("/content/description").asString()).isEqualTo(
                "인증 · 판정 · 사진 2장 · 답변 대기 3건\n11-11 14:45 KST · https://admin.ruleup.co.kr/inquiries?inquiry=" + INQUIRY);
    }

    @Test
    @DisplayName("사진이 없으면 사진 문구를 빼고, 모르는 분류는 코드 그대로 둔다")
    void noImageAndUnknownCategory() {
        assertThat(InquirySlackAlert.description("ERROR_ETC", 0, null, Instant.now(), INQUIRY))
                .startsWith("오류 · 제안 · 기타 · 답변 대기 ?건");
        assertThat(InquirySlackAlert.categoryLabel("PAYMENT")).isEqualTo("PAYMENT");
    }

    @Test
    @DisplayName("토픽이 비면 아무것도 하지 않는다 — DB 도 SNS 도 건드리지 않는다")
    void disabledWithoutTopic() {
        InquirySlackAlert alert = new InquirySlackAlert(new OpsSlackChannel(""), null);

        assertThatCode(() -> alert.received(INQUIRY, "ACCOUNT_LOGIN", 0, Instant.now())).doesNotThrowAnyException();
    }
}
