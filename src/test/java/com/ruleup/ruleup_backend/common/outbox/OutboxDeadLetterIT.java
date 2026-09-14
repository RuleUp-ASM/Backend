package com.ruleup.ruleup_backend.common.outbox;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아웃박스가 <b>포기한 메시지</b>를 처리 완료와 구분하는지 (알림 공통 5-1 · 인증 공통 5-7).
 *
 * <p>재시도 상한을 넘긴 건을 {@code processedAt} 으로 닫으면 두 가지가 무너진다.
 * <ul>
 *   <li>발행된 건과 끝내 못 나간 건이 <b>같은 모양</b>이 된다 — 감시자 통지·강퇴·감점이 조용히
 *       사라져도 집계는 정상으로 보인다.</li>
 *   <li>{@code dedupKey} 행이 남아 <b>같은 사건의 재적재가 영구히 막힌다.</b></li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxDeadLetterIT {

    private static final String TYPE = "TEST_DEAD_LETTER";

    @Autowired OutboxRepository repository;
    @Autowired OutboxService outboxService;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private OutboxMessage exhaust(String dedupKey) {
        OutboxMessage m = repository.save(
                OutboxMessage.of(TYPE, "{\"v\":1}", dedupKey, Instant.now()));
        for (int i = 0; i < OutboxMessage.MAX_ATTEMPTS; i++) {
            m.markFailed(Instant.now(), "수신측 장애");
        }
        return repository.save(m);
    }

    @Test
    @DisplayName("[P1] 포기한 메시지는 처리 완료로 세지 않고, 스윕이 다시 집지도 않는다")
    void deadLetteredIsNeitherProcessedNorRetried() {
        OutboxMessage dead = exhaust("dead:" + UUID.randomUUID());

        assertThat(dead.isDeadLettered()).isTrue();
        assertThat(dead.getProcessedAt())
                .as("발행되지 않았는데 처리 완료로 찍으면 집계가 거짓말을 한다")
                .isNull();
        assertThat(dead.isPending())
                .as("스윕이 다시 집으면 뒤에 쌓인 정상 건까지 굶는다")
                .isFalse();
        assertThat(repository.findDue(Instant.now().plusSeconds(86_400), Limit.of(500)))
                .extracting(OutboxMessage::getId)
                .doesNotContain(dead.getId());
        assertThat(repository.findDeadLettered(Limit.of(100)))
                .as("운영이 찾을 수 있어야 되살릴 수 있다")
                .extracting(OutboxMessage::getId)
                .contains(dead.getId());
    }

    @Test
    @DisplayName("[P1] 처리 완료 표시가 남아 있던 과거 메시지도 되살리면 다시 집힌다")
    void aMigratedMessageBecomesPendingAgainAfterRedrive() {
        // V49 이전 구조를 재현한다 — 포기한 건에 processedAt 이 찍혀 있던 상태.
        OutboxMessage dead = exhaust("migrated:" + UUID.randomUUID());
        jdbcTemplate.update("UPDATE outbox_messages SET processed_at = dead_lettered_at WHERE id = ?",
                (Object) uuidBytes(dead.getId()));

        int redriven = dispatcher.redriveDeadLettered(100);
        assertThat(redriven).isGreaterThanOrEqualTo(1);

        OutboxMessage revived = repository.findById(dead.getId()).orElseThrow();
        assertThat(revived.isPending())
                .as("processedAt 을 함께 비우지 않으면 되살렸다고 해 놓고 폴러가 집지 않는다 — "
                        + "하필 그 행들이 실제로 나가지 못한 통지·집행이다")
                .isTrue();
        assertThat(repository.findDue(Instant.now().plusSeconds(60), Limit.of(500)))
                .extracting(OutboxMessage::getId)
                .contains(dead.getId());
    }

    private static byte[] uuidBytes(UUID id) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    @Test
    @DisplayName("[P1] 같은 사건이 다시 적재되면 포기했던 메시지가 되살아난다")
    void reEnqueueRevivesADeadLetteredMessage() {
        String dedupKey = "revive:" + UUID.randomUUID();
        UUID deadId = exhaust(dedupKey).getId();

        // 같은 dedupKey 로 다시 적재 — 예전에는 「이미 있다」로 조용히 건너뛰었다.
        outboxService.enqueue(TYPE, java.util.Map.of("v", 2), dedupKey);

        OutboxMessage revived = repository.findById(deadId).orElseThrow();
        assertThat(revived.isDeadLettered())
                .as("한 번 죽은 사건이 영원히 발행 불가가 되면 안 된다")
                .isFalse();
        assertThat(revived.isPending()).isTrue();
        assertThat(revived.getAttempts()).isZero();
    }
}
