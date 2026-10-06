package com.ruleup.ruleup_backend.common.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class ClientErrorLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ClientErrorLog.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach void attach() { appender.start(); logger.addAppender(appender); }
    @AfterEach void detach() { logger.detachAppender(appender); }

    @Test
    @DisplayName("4xx 한 건을 상태·코드·메서드·경로 패턴 한 줄로 남긴다 — 대시보드가 이 줄로 코드별·API별로 집계한다")
    void logsOneLinePerClientError() {
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/v1/challenges/0199aa00-1111-7222-8333-444455556666");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/challenges/{challengeId}");

        ClientErrorLog.record(req, ErrorCode.CHALLENGE_NOT_EDITABLE);

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("client_error status=409 code=CHALLENGE_NOT_EDITABLE method=PATCH route=/api/v1/challenges/{challengeId}");
    }

    @Test
    @DisplayName("보안 필터처럼 경로 패턴이 아직 없으면 URI 의 UUID·숫자를 {id} 로 묶는다 — 사용자마다 다른 줄이 되지 않게")
    void normalizesRawPathWhenPatternMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/v1/challenges/0199aa00-1111-7222-8333-444455556666/members/42");

        ClientErrorLog.record(req, ErrorCode.LOGIN_REQUIRED);

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("client_error status=401 code=LOGIN_REQUIRED method=GET route=/api/v1/challenges/{id}/members/{id}");
    }

    @Test
    @DisplayName("5xx 는 남기지 않는다 — 서버 오류는 이미 ERROR 로그와 5xx 경보가 따로 본다")
    void ignoresServerErrors() {
        ClientErrorLog.record(new MockHttpServletRequest("GET", "/api/v1/x"), ErrorCode.INTERNAL_SERVER_ERROR);

        assertThat(appender.list).isEmpty();
    }
}
