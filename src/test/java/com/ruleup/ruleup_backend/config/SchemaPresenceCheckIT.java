package com.ruleup.ruleup_backend.config;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaPresenceCheckIT {

    @Autowired SchemaPresenceCheck check;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("마이그레이션으로 만든 스키마는 엔티티가 기대하는 테이블·컬럼을 전부 갖고 있다")
    void currentSchemaIsComplete() {
        assertThat(check.missing()).isEmpty();
    }

    @Test
    @DisplayName("엔티티가 기대하는 컬럼이 없으면 기동을 멈춘다 — 떠서 500 을 내는 대신 배포가 되돌려진다")
    void missingColumnStopsStartup() {
        jdbc.execute("ALTER TABLE VerificationDaily RENAME COLUMN acknowledgedAt TO acknowledgedAt_tmp");
        try {
            assertThat(check.missing()).contains("verificationdaily.acknowledgedat");
            assertThatThrownBy(check::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("verificationdaily.acknowledgedat");
        } finally {
            jdbc.execute("ALTER TABLE VerificationDaily RENAME COLUMN acknowledgedAt_tmp TO acknowledgedAt");
        }
    }
}
