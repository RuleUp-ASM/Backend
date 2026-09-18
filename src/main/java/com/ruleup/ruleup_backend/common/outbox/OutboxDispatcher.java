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

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /** 자동 재적재 한 묶음 크기. 남은 게 없을 때까지 이어 돌린다. */
    private static final int REDRIVE_BATCH = 500;

    /** 이어 돌릴 최대 횟수. 한 번에 25만 건이면 어떤 장애 복구에도 충분하고, 폭주도 막는다. */
    private static final int MAX_REDRIVE_PASSES = 500;

    /**
     * 즉시 경로 전용 스레드. 큐가 1 인 것은 의도다 — 「한 번 더 흘려라」가 여러 건 쌓여 봐야
     * 하는 일은 같고, 넘치면 버려도 스윕이 받쳐 준다.
     */
    private final ThreadPoolExecutor flusher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            r -> Thread.ofPlatform().name("outbox-flush").daemon(true).unstarted(r),
            new ThreadPoolExecutor.DiscardPolicy());

    private final OutboxRepository repository;
    /**
     * 포기 카운터. 정정 전파 실패를 포함해 <b>끝내 나가지 못한 발행</b>이 여기 모인다 —
     * 스펙이 0건을 요구하는 값이라 알람을 걸 수 있는 형태로 내보낸다.
     */
    private final io.micrometer.core.instrument.Counter deadLettered;
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
                            io.micrometer.core.instrument.MeterRegistry registry,
                            @org.springframework.context.annotation.Lazy OutboxDispatcher self) {
        this.repository = repository;
        this.handlerProvider = handlerProvider;
        this.self = self;
        this.deadLettered = io.micrometer.core.instrument.Counter.builder("outbox.dead_lettered")
                .description("재시도 상한을 넘겨 포기한 발행 — 수신측에 도달하지 않은 사건")
                .register(registry);
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
     * 커밋 직후 한 번 흘려 달라는 요청.
     *
     * <p><b>부른 스레드에서 흘리지 않는다.</b> 예전에는 {@code afterCommit} 콜백이 그대로
     * 발행까지 수행했는데, 그러면 요청을 처리하던 HTTP 스레드가 한 묶음(200건)을 다 밀어낼
     * 때까지 붙잡힌다 — 이의 한 건을 넣었을 뿐인데 남의 알림·정정까지 그 응답이 떠안고,
     * 아웃박스로 옮겨 비동기로 만든 이상탐지의 30일 조회도 응답 전에 돌아 버린다.
     * 아웃박스의 요점은 <b>커밋과 발행을 떼는 것</b>인데 스레드가 붙어 있으면 뗀 것이 아니다.
     *
     * <p>그래서 신호만 보내고 전용 스레드가 흘린다. 큐가 차 있으면 그냥 버린다 — 이미 흘릴
     * 일이 예약돼 있다는 뜻이고, 무엇보다 <b>유실을 막는 것은 스윕</b>이라 이 경로는 처음부터
     * 지연을 줄이는 최적화일 뿐이다. 그래서 여기서 실패해도 조용히 넘어간다.
     */
    public void requestFlush() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submitFlush();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitFlush();
            }
        });
    }

    private void submitFlush() {
        try {
            flusher.execute(this::safeFlush);
        } catch (RejectedExecutionException ignored) {
            // 이미 흘릴 일이 예약돼 있거나 종료 중이다. 스윕이 집는다.
        }
    }

    @PreDestroy
    void shutdownFlusher() {
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(5, TimeUnit.SECONDS)) flusher.shutdownNow();
        } catch (InterruptedException e) {
            flusher.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
     * 한 건 처리. 핸들러 트랜잭션이 롤백된 뒤 실패 기록을 별도 트랜잭션에 남긴다.
     * REQUIRED 핸들러가 rollback-only로 표시한 트랜잭션에서 예외만 잡으면 attempts도
     * 롤백되어 같은 불량 메시지가 영원히 첫 순서를 차지한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean processOne(java.util.UUID id) {
        try {
            return self.deliver(id);
        } catch (Exception e) {
            self.recordFailure(id, e);
            return false;
        }
    }

    /** 핸들러의 DB 변경과 처리 완료 표시는 같은 트랜잭션으로 커밋하거나 롤백한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(java.util.UUID id) {
        OutboxMessage message = repository.findByIdForUpdate(id).orElse(null);
        Instant now = Instant.now();
        if (message == null || !message.isPending() || message.getAvailableAt().isAfter(now)) return false;

        OutboxHandler handler = handlers().get(message.getType());
        if (handler == null) {
            throw new IllegalStateException("NO_HANDLER: " + message.getType());
        }
        handler.handle(message.getPayload());
        message.markProcessed(now);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(java.util.UUID id, Exception error) {
        OutboxMessage message = repository.findByIdForUpdate(id).orElse(null);
        Instant now = Instant.now();
        // 롤백과 기록 사이에 다른 소비자가 성공했거나 백오프를 기록했을 수 있다.
        if (message == null || !message.isPending() || message.getAvailableAt().isAfter(now)) return;
        message.markFailed(now, error.toString());
        if (message.isDeadLettered()) {
            deadLettered.increment();
            log.error("아웃박스 발행 포기 — 이 사건은 수신측에 도달하지 않았다. type={} id={} err={}",
                    message.getType(), id, error.toString(), error);
        } else {
            log.warn("아웃박스 처리 실패 type={} id={} attempts={}: {}",
                    message.getType(), id, message.getAttempts(), error.toString());
        }
    }

    /**
     * 매일 04:40 KST — 최근에 포기한 발행을 <b>한 번 더</b> 돌린다.
     *
     * <p>포기 사유의 대부분은 수신측의 일시 장애다. 그런데 재시도 상한(5회·누적 15분)이 짧아
     * 몇 시간짜리 장애에는 통째로 걸린다 — 이의 인용 점수 정정이 그 사이 5회 실패하면
     * <b>사용자에게는 인용됐다고 응답해 놓고 점수는 끝내 안 돌아온다.</b>
     *
     * <p><b>창을 두지 않는다.</b> 「최근 하루」로 묶으면 그보다 오래 끄는 장애에서 메시지가
     * 자동 대상에서 빠져 수동 복구만 남는다 — 스펙의 「성공할 때까지 멱등 재시도」가 하루짜리
     * 약속이 되어 버린다. 영구히 고칠 수 없는 메시지를 매일 한 번 더 태우는 비용은 하루 한 번이라
     * 무시할 만하고, 그렇게 계속 죽는 건은 {@code outbox.dead_lettered.oldest_age_seconds} 가
     * 자라는 것으로 드러난다 — 목록이 비지 않는다는 사실 자체가 신호다.
     */
    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    public int redriveRecentDeadLettered() {
        Instant since = Instant.EPOCH;
        int total = 0;
        // <b>남은 게 없을 때까지</b> 이어 돌린다. 한 묶음만 처리하고 끝내면 그 수를 넘긴 건은
        // 창이 지나 영구 잔류한다 — 「성공할 때까지 멱등 재시도」가 한 번으로 끝나 버린다.
        for (int pass = 0; pass < MAX_REDRIVE_PASSES; pass++) {
            int redriven = self.redriveDeadLettered(REDRIVE_BATCH, since);
            total += redriven;
            if (redriven < REDRIVE_BATCH) break;
        }
        if (total > 0) safeFlush();   // 되살린 건을 곧바로 흘린다
        return total;
    }

    /**
     * 발행에 실패한 채 닫힌 메시지를 다시 줄에 세운다 — 운영 복구 경로.
     *
     * <p>원인(수신측 장애·배포 롤백 등)이 해소된 뒤 부르면 그때부터 정상 재시도가 돈다.
     * 핸들러가 멱등하므로 이미 일부 처리된 건이 섞여 있어도 안전하다.
     *
     * @return 다시 줄에 세운 건수
     */
    @Transactional
    public int redriveDeadLettered(int limit) {
        return redriveDeadLettered(limit, Instant.EPOCH);
    }

    /**
     * @param since 이 시각 이후에 포기한 건만 대상으로 한다. 전체를 보려면 {@link Instant#EPOCH}.
     */
    @Transactional
    public int redriveDeadLettered(int limit, Instant since) {
        List<OutboxMessage> dead = repository.findDeadLetteredSince(since, Limit.of(limit));
        Instant now = Instant.now();
        dead.forEach(m -> m.redrive(now));
        if (!dead.isEmpty()) log.warn("아웃박스 포기분 재적재 {}건 (since={})", dead.size(), since);
        return dead.size();
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
