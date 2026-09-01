package com.ruleup.ruleup_backend.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * 발행 대기함 적재 — <b>호출자의 트랜잭션 안에서 DB 만 건드린다.</b>
 *
 * <p>여기서 외부 호출을 하지 않는 것이 규칙이다. 아웃박스의 값어치는 "도메인 커밋과 발행 의사가
 * 같은 원자 단위"라는 점 하나인데, 적재 단계에서 FCM 이든 무엇이든 밖으로 나가는 순간 그 성질이
 * 깨진다. 실제 발행은 {@link OutboxDispatcher} 가 커밋 이후에 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxRepository repository;

    /**
     * 발행 예약. {@code dedupKey} 가 있으면 같은 키의 행이 이미 있을 때 조용히 건너뛴다.
     *
     * <p>전파는 {@code REQUIRED} 다 — 도메인 트랜잭션이 열려 있으면 <b>그 트랜잭션에 합류</b>해
     * 같은 커밋에 들어가고(아웃박스의 존재 이유), 배치처럼 묶을 도메인 커밋이 없으면 자기 트랜잭션을
     * 열어 곧바로 커밋한다. {@code REQUIRES_NEW} 로 두면 도메인이 롤백돼도 발행만 남아 고치려던
     * 문제가 그대로 돌아온다.
     *
     * @param dedupKey 같은 사건을 두 번 적지 않기 위한 키. 중복 제어가 필요 없으면 null
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(String type, Object payload, String dedupKey) {
        if (dedupKey != null && repository.findByDedupKey(dedupKey).isPresent()) {
            log.debug("아웃박스 중복 — 적재하지 않는다. type={} dedupKey={}", type, dedupKey);
            return;
        }
        repository.save(OutboxMessage.of(type, JSON.writeValueAsString(payload), dedupKey, Instant.now()));
    }

    public static <T> T parse(String payload, Class<T> type) {
        return JSON.readValue(payload, type);
    }
}
