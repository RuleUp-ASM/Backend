package com.ruleup.ruleup_backend.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** OAuth 제공자 전용 HTTP 제한 시간. 운영 환경변수로 조정할 수 있다. */
@ConfigurationProperties(prefix = "app.oauth.http")
public record OAuthHttpProperties(
        Duration connectionRequestTimeout,
        Duration connectTimeout,
        Duration responseTimeout
) {
    private static final Duration DEFAULT_CONNECTION_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    public OAuthHttpProperties {
        connectionRequestTimeout = positiveOrDefault(
                connectionRequestTimeout, DEFAULT_CONNECTION_REQUEST_TIMEOUT);
        connectTimeout = positiveOrDefault(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        responseTimeout = positiveOrDefault(responseTimeout, DEFAULT_RESPONSE_TIMEOUT);
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }
}
