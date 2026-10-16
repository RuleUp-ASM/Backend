package com.ruleup.ruleup_backend.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class DbUnavailableResponseTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void poolTimeoutIs503WithRetryAfter() {
        // Hikari 대기 초과가 트랜잭션 시작 실패로 감싸여 올라오는 모양 그대로
        var e = new CannotCreateTransactionException("Could not open JPA EntityManager",
                new JpaSystemException(new RuntimeException(
                        new SQLTransientConnectionException("ruleup-main - Connection is not available, request timed out after 5000ms"))));
        var res = handler.handleUnexpected(e);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(res.getBody().error().code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void ordinaryBugStays500() {
        var res = handler.handleUnexpected(new IllegalStateException("boom"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
    }
}
