package com.ruleup.ruleup_backend.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 발행 대기함 소비 — 커밋된 행만 읽어 실제 발행을 수행한다.
 *
 * <h4>두 개의 기동 경로</h4>
 * <ul>
 *   <li><b>커밋 직후 즉시</b> — 도메인 트랜잭션이 커밋되면 곧바로 한 번 흘린다. 필수(A) 고지가
 *       스윕 주기만큼 늦게 도착하면 "제재는 걸렸는데 왜 안 알려주냐"가 된다.</li>
 *   <li><b>주기 스윕</b> — 위 즉시 경로는 <b>보장이 아니라 최적화</b>다. 커밋 직후 서버가 죽으면
 *       콜백은 사라지지만 행은 남아 있고, 이 스윕이 반드시 줍는다. <b>유실을 막는 것은 스윕
 *       쪽</b>이며 즉시 경로는 지연만 줄인다.</li>
 * </ul>
 *
 * <h4>중복은 허용하고 유실은 허용하지 않는다</h4>
 * 발행에는 성공했는데 {@code processed_at} 을 남기기 전에 죽는 창은 2PC 없이는 없앨 수 없다.
 * 그래서 <b>at-least-once</b> 를 택하고 핸들러 쪽을 멱등하게 만든다 — 반대로 "먼저 처리 표시,
 * 그다음 발행"으로 두면 그 창이 통째로 유실이 된다.
 */
@Slf4j
@Component
public class OutboxDispatcher {

    /** 한 번에 흘릴 상한. 뒤에 밀린 건은 다음 사이클이 가져간다. */
    private static final int BATCH_SIZE = 200;

    /** 처리 완료분 보관 기간 — 장애 조사에 쓰고 그 뒤에는 지운다. */
    private static final Duration RETENTION = Duration.ofDays(14);

    private final OutboxRepository repository;
    /**
     * 핸들러는 <b>지연 해석</b>한다. 생성자에서 {@code List<OutboxHandler>} 를 받으면
     * 발행자 → 디스패처 → 핸들러 → 발행자 순환이 생겨 컨텍스트가 뜨지 않는다 — 아웃박스는
     * 원래 발행하는 쪽이 부르는 물건이라 이 순환은 구조상 피할 수 없다.
     */
    private final ObjectProvider<OutboxHandler> handlerProvider;
    private final OutboxDispatcher self;

    /** 첫 사용 시 한 번만 만든다. 핸들러 집합은 기동 후 바뀌지 않는다. */
    private volatile Map<String, OutboxHandler> handlers;

    public OutboxDispatcher(OutboxRepository repository, ObjectProvider<OutboxHandler> handlerProvider,
                            @org.springframework.context.annotation.Lazy OutboxDispatcher self) {
        this.repository = repository;
        this.handlerProvider = handlerProvider;
        this.self = self;
    }

    private Map<String, OutboxHandler> handlers() {
        Map<String, OutboxHandler> resolved = handlers;
        if (resolved == null) {
            resolved = handlerProvider.stream()
                    .collect(Collectors.toMap(OutboxHandler::type, Function.identity()));
            handlers = resolved;
        }
        return resolved;
    }

    /**
     * 커밋 직후 한 번 흘려 달라는 요청. 트랜잭션이 없으면 즉시 흘린다.
     *
     * <p>여기서 실패해도 조용히 넘어간다 — 스윕이 다시 집기 때문이다. 이 호출이 실패했다고
     * 도메인 트랜잭션에 영향을 주면 아웃박스를 쓰는 이유가 없어진다.
     */
    public void requestFlush() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeFlush();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeFlush();
            }
        });
    }

    /** 주기 스윕 — 유실을 막는 쪽. 즉시 경로가 죽어도 여기서 반드시 복구된다. */
    @Scheduled(fixedDelayString = "${app.outbox.sweep-interval-ms:30000}")
    public void sweep() {
        safeFlush();
    }

    private void safeFlush() {
        try {
            self.flush();
        } catch (Exception e) {
            log.warn("아웃박스 발행 실패 — 스윕이 다시 집는다: {}", e.toString());
        }
    }

    /**
     * 차례가 된 건을 처리한다.
     *
     * <p><b>메시지 한 건마다 트랜잭션을 따로 연다.</b> 한 건이 터졌다고 같은 배치의 나머지 성공분이
     * 롤백돼 다시 발행되면 중복이 늘어난다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int flush() {
        Instant now = Instant.now();
        List<OutboxMessage> due = repository.findDue(now, Limit.of(BATCH_SIZE));
        int processed = 0;
        for (OutboxMessage message : due) {
            if (self.processOne(message.getId())) processed++;
        }
        if (processed > 0) log.debug("아웃박스 발행 {}건", processed);
        return processed;
    }

    /**
     * 한 건 처리. 다른 인스턴스가 같은 행을 동시에 집는 것은 비관 락으로 막고, 락을 얻은 뒤
     * <b>다시 미처리인지 확인</b>한다 — 기다리는 동안 상대가 끝냈을 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(java.util.UUID id) {
        OutboxMessage message = repository.findByIdForUpdate(id).orElse(null);
        if (message == null || !message.isPending()) return false;

        Instant now = Instant.now();
        OutboxHandler handler = handlers().get(message.getType());
        if (handler == null) {
            // 핸들러가 없는 타입은 재시도해도 달라지지 않는다 — 배포 롤백 같은 상황이므로 남겨만 둔다.
            log.error("아웃박스 핸들러 없음 type={} id={}", message.getType(), id);
            message.markFailed(now, "NO_HANDLER: " + message.getType());
            return false;
        }
        try {
            handler.handle(message.getPayload());
            message.markProcessed(now);
            return true;
        } catch (Exception e) {
            log.warn("아웃박스 처리 실패 type={} id={} attempts={}: {}",
                    message.getType(), id, message.getAttempts() + 1, e.toString());
            message.markFailed(now, e.toString());
            return false;
        }
    }

    /** 보관 기간 경과분 정리. 점검 창(02:00~03:00)과 아침 요약(08:00)을 피한다. */
    @Scheduled(cron = "0 50 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public int purgeProcessed() {
        List<OutboxMessage> old = repository.findProcessedBefore(
                Instant.now().minus(RETENTION), Limit.of(1000));
        if (old.isEmpty()) return 0;
        repository.deleteAll(old);
        log.info("아웃박스 보관 기간 경과분 정리 — {}건", old.size());
        return old.size();
    }
}
