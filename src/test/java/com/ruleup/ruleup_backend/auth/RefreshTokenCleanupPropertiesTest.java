package com.ruleup.ruleup_backend.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCleanupPropertiesTest {

    @Test
    void uses_selected_retention_and_safe_batch_defaults() {
        RefreshTokenCleanupProperties properties =
                new RefreshTokenCleanupProperties(null, 0, -1, null);

        assertThat(properties.ordinaryRetentionDays()).isEqualTo(30);
        assertThat(properties.reuseDetectedRetentionDays()).isEqualTo(180);
        assertThat(properties.batchSize()).isEqualTo(1_000);
        assertThat(properties.maxBatchesPerType()).isEqualTo(100);
    }

    @Test
    void keeps_positive_overrides() {
        RefreshTokenCleanupProperties properties =
                new RefreshTokenCleanupProperties(45, 365, 500, 20);

        assertThat(properties.ordinaryRetentionDays()).isEqualTo(45);
        assertThat(properties.reuseDetectedRetentionDays()).isEqualTo(365);
        assertThat(properties.batchSize()).isEqualTo(500);
        assertThat(properties.maxBatchesPerType()).isEqualTo(20);
    }
}
