package com.ruleup.ruleup_backend.observability.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SignupSlackAlertTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER = "0b9c1f5e-2a3d-4c5b-8e7f-123456789abc";

    @Test
    @DisplayName("Amazon Q 커스텀 알림 형식으로 만든다 — 환경·소셜 종류·누적 회원 수·KST 시각")
    void customNotificationShape() {
        String body = SignupSlackAlert.message("prod", "KAKAO", false, 1234L,
                Instant.parse("2026-10-23T04:20:00Z"), USER);

        JsonNode node = JSON.readTree(body);
        assertThat(node.get("version").asString()).isEqualTo("1.0");
        assertThat(node.get("source").asString()).isEqualTo("custom");
        assertThat(node.at("/content/textType").asString()).isEqualTo("client-markdown");
        assertThat(node.at("/content/title").asString()).isEqualTo(":tada: [prod] 새 가입");
        assertThat(node.at("/content/description").asString())
                .isEqualTo("카카오 로그인 · 누적 회원 1,234명\n10-23 13:20 KST · `" + USER + "`");
    }

    @Test
    @DisplayName("탈퇴 계정 복귀는 새 가입과 제목을 가른다")
    void restoredHasOwnTitle() {
        String body = SignupSlackAlert.message("stg", "apple", true, null, Instant.now(), USER);

        assertThat(JSON.readTree(body).at("/content/title").asString()).isEqualTo(":leftwards_arrow_with_hook: [stg] 탈퇴 계정 복귀");
        assertThat(JSON.readTree(body).at("/content/description").asString()).startsWith("애플 로그인 · 누적 회원 ?명");
    }

    @Test
    @DisplayName("토픽 이름 ruleup-<env>-alerts 에서 환경을 뽑는다")
    void envFromTopicArn() {
        assertThat(SignupSlackAlert.envOf("arn:aws:sns:ap-northeast-2:961178969292:ruleup-prod-alerts")).isEqualTo("prod");
        assertThat(SignupSlackAlert.envOf("arn:aws:sns:ap-northeast-2:961178969292:ruleup-stg-alerts")).isEqualTo("stg");
    }

    @Test
    @DisplayName("토픽이 비면 아무것도 하지 않는다 — DB 도 SNS 도 건드리지 않는다")
    void disabledWithoutTopic() {
        SignupSlackAlert alert = new SignupSlackAlert("  ", null);

        assertThatCode(() -> alert.signedUp(USER, "GOOGLE", false)).doesNotThrowAnyException();
    }
}
