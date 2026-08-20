package com.ruleup.ruleup_backend.oauth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthHttpPropertiesTest {

    @Test
    void uses_fast_fail_defaults_for_missing_or_invalid_values() {
        OAuthHttpProperties properties = new OAuthHttpProperties(null, Duration.ZERO, Duration.ofSeconds(-1));

        assertThat(properties.connectionRequestTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void keeps_positive_overrides() {
        OAuthHttpProperties properties = new OAuthHttpProperties(
                Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(4));

        assertThat(properties.connectionRequestTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(4));
    }
}
