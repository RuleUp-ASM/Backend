package com.ruleup.ruleup_backend.common.outbox;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({TestcontainersConfiguration.class, OutboxRollbackIT.Handlers.class})
class OutboxRollbackIT {
    private static final String TYPE = "TEST_TRANSACTIONAL_FAILURE";
    @Autowired OutboxRepository repository;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class Handlers {
        @Bean FailingHandler failingHandler(OutboxService outbox) { return new FailingHandler(outbox); }
    }

    static class FailingHandler implements OutboxHandler {
        private final OutboxService outbox;
        FailingHandler(OutboxService outbox) { this.outbox = outbox; }
        @Override public String type() { return TYPE; }

        @Override
        @Transactional
        public void handle(String payload) {
            String key = OutboxService.parse(payload, String.class);
            if (key.startsWith("fail:")) {
                outbox.enqueue("TEST_ROLLBACK_PROBE", java.util.Map.of(), key);
                // 실제 점수 핸들러처럼 참여 트랜잭션을 rollback-only로 만든다.
                throw new IllegalStateException("injected transactional failure");
            }
        }
    }

    @Test
    void rollbackPersistsBackoffAndDoesNotStarveTheNextMessage() {
        String failedKey = "fail:" + UUID.randomUUID();
        var failed = repository.saveAndFlush(OutboxMessage.of(TYPE, "\"" + failedKey + "\"", failedKey,
                Instant.now().minusSeconds(2)));
        var healthy = repository.saveAndFlush(OutboxMessage.of(TYPE, "\"ok\"", "ok:" + UUID.randomUUID(),
                Instant.now().minusSeconds(1)));

        dispatcher.flush();

        var state = repository.findById(failed.getId()).orElseThrow();
        assertThat(state.getAttempts()).isEqualTo(1);
        assertThat(state.getAvailableAt()).isAfter(Instant.now());
        assertThat(state.getLastError()).contains("injected transactional failure");
        assertThat(state.getProcessedAt()).isNull();
        assertThat(repository.findById(healthy.getId()).orElseThrow().getProcessedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE type='TEST_ROLLBACK_PROBE' AND dedup_key=?",
                Integer.class, failedKey)).isZero();

        // 이미 조회한 오래된 due 목록으로 호출해도 백오프를 건너뛰지 않는다.
        assertThat(dispatcher.processOne(failed.getId())).isFalse();
        assertThat(repository.findById(failed.getId()).orElseThrow().getAttempts()).isEqualTo(1);
        for (int attempt = 2; attempt <= OutboxMessage.MAX_ATTEMPTS; attempt++) {
            jdbc.update("UPDATE outbox_messages SET available_at=UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id=UUID_TO_BIN(?)",
                    failed.getId().toString());
            assertThat(dispatcher.processOne(failed.getId())).isFalse();
            assertThat(repository.findById(failed.getId()).orElseThrow().getAttempts()).isEqualTo(attempt);
        }
        var dead = repository.findById(failed.getId()).orElseThrow();
        assertThat(dead.isDeadLettered()).isTrue();
        assertThat(dead.getProcessedAt()).isNull();
    }
}
