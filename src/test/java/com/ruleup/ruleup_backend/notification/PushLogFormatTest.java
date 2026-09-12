package com.ruleup.ruleup_backend.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.notification.consumer.NotificationDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 로그가 <b>파싱 가능한 JSON 한 줄</b>인지.
 *
 * <p>CloudWatch 메트릭 필터의 JSON 문법은 로그 이벤트 전체가 유효한 JSON 이어야 동작한다.
 * 한 글자만 어긋나도 필터는 <b>에러 없이 0건을 세고</b>, 야간 0건 알람이 초록불로 남는다 —
 * 스펙이 이번 재설계의 유일한 실질 리스크로 지목한 상태다. 그래서 형식 자체를 테스트로 잡는다.
 *
 * <p>{@code nightPush}·{@code outsideMarketingWindow} 를 서버가 계산해 싣는 이유도 같다.
 * 메트릭 필터는 시간 조건을 표현하지 못해서, 이 값이 없으면 「KST 21~08 에 SUCCESS 가
 * 있었나」를 물을 방법이 아예 없다.
 */
class PushLogFormatTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper OM = new ObjectMapper();

    private Logger pushLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void attach() {
        pushLogger = (Logger) LoggerFactory.getLogger("notification.push");
        captured = new ListAppender<>();
        captured.start();
        pushLogger.addAppender(captured);
    }

    @AfterEach
    void detach() {
        pushLogger.detachAppender(captured);
    }

    private JsonNode emit(String result, Instant at) throws Exception {
        Method log = NotificationDispatcher.class.getDeclaredMethod("logResult",
                com.ruleup.ruleup_backend.notification.queue.NotificationMessage.class,
                String.class,
                com.ruleup.ruleup_backend.notification.consumer.SuppressedReason.class,
                String.class, Instant.class);
        log.setAccessible(true);

        // 의존성은 전부 null 이어도 된다 — 여기서 부르는 것은 로그 한 줄을 찍는 logResult 뿐이고
        // 그 메서드는 어떤 리포지터리도 건드리지 않는다. 인자 수가 늘면 여기만 맞춰 주면 된다.
        NotificationDispatcher dispatcher = new NotificationDispatcher(
                null, null, null, null, null, null);
        log.invoke(dispatcher, message(), result, null, null, at);

        return OM.readTree(captured.list.getLast().getFormattedMessage());
    }

    private static com.ruleup.ruleup_backend.notification.queue.NotificationMessage message() {
        var type = com.ruleup.ruleup_backend.notification.domain.NotificationType.MARKETING;
        return new com.ruleup.ruleup_backend.notification.queue.NotificationMessage(
                UUID.randomUUID(), UUID.randomUUID(), type.name(), type.toggleGroup(), null,
                type.tab(), "제목", "본문", null, null);
    }

    private static Instant kst(int hour) {
        return LocalDateTime.of(2026, 9, 8, hour, 0).atZone(KST).toInstant();
    }

    @Test
    @DisplayName("한 줄이 통째로 유효한 JSON 이다 — 아니면 메트릭 필터가 조용히 0건을 센다")
    void lineIsValidJson() throws Exception {
        JsonNode node = emit("SUCCESS", kst(12));

        assertThat(node.get("evt").asText()).isEqualTo("push.result");
        assertThat(node.get("result").asText()).isEqualTo("SUCCESS");
        assertThat(node.get("notificationId").asText()).isNotBlank();
        assertThat(node.get("userId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("야간 발송 여부를 서버가 계산해 싣는다 — 필터가 시간 조건을 표현하지 못한다")
    void carriesNightFlag() throws Exception {
        assertThat(emit("SUCCESS", kst(22)).get("nightPush").asBoolean()).isTrue();
        assertThat(emit("SUCCESS", kst(12)).get("nightPush").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("마케팅 창 밖 발송 여부도 함께 싣는다")
    void carriesMarketingWindowFlag() throws Exception {
        assertThat(emit("SUCCESS", kst(22)).get("outsideMarketingWindow").asBoolean()).isTrue();
        assertThat(emit("SUCCESS", kst(12)).get("outsideMarketingWindow").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("로그에 FCM 토큰과 알림 본문을 넣지 않는다 — userId·notificationId 까지만")
    void carriesNoSensitiveContent() throws Exception {
        JsonNode node = emit("SUCCESS", kst(12));

        assertThat(node.has("body")).isFalse();
        assertThat(node.has("title")).isFalse();
        assertThat(node.toString()).doesNotContain("본문").doesNotContain("제목");
    }
}
