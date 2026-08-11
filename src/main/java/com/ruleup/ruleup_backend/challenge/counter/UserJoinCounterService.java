package com.ruleup.ruleup_backend.challenge.counter;

import com.ruleup.ruleup_backend.challenge.repository.UserChallengeCounterRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.UUID;

/**
 * 동시 참여 슬롯({@code user_challenge_counters.active_join_count})의 진실을 독점하는 서비스.
 *
 * <p><b>왜 증분(±1)이 아니라 재계산인가.</b> 슬롯을 잡고 푸는 지점이 여섯 곳(생성·가입·탈퇴·강퇴·종료·삭제)이라
 * 하나만 빠져도 값이 조용히 어긋나고, 어긋난 사용자는 새 챌린지 가입이 {@code FREE_LIMIT} 으로 막힌다.
 * 실제로 종료·자동강퇴·회원탈퇴 세 경로가 빠져 있었다. 증분은 "빠진 곳을 전부 찾았다"는 증명이 필요하지만
 * 재계산은 그 증명이 필요 없다 — 멱등이라 두 번 돌아도, 중간에 실패해도 결과가 같다.
 * (통계 Projection 이 같은 이유로 같은 선택을 했다 — {@code ChallengeStatsProjectionService} 주석 참조)
 *
 * <p><b>세 층으로 막는다. 서로 대체재가 아니다.</b>
 * <ul>
 *   <li>L0 게이트 시점 — 가입이 저장값이 아니라 {@link #countActiveSlots} 로 판정한다.
 *       저장값이 어긋나 있어도 사용자가 시도하는 순간은 정확하다.</li>
 *   <li>L1 이벤트 시점 — 종료·삭제·강퇴·탈퇴 직후 {@link #recompute} 로 저장값을 맞춘다.
 *       공개 상세의 {@code joinBlockReason} 은 저장값을 읽으므로 L0 만으로는 화면이 거짓말을 한다.</li>
 *   <li>L2 보정 배치 — {@code UserJoinCounterReconciliationService}. 이벤트 유실·서버 다운 대비.</li>
 * </ul>
 *
 * <p><b>어느 메서드를 언제 쓰는지가 이 클래스에서 가장 틀리기 쉬운 부분이다.</b>
 * 각 메서드의 주석을 반드시 읽을 것.
 */
@Service
public class UserJoinCounterService {

    private static final Logger log = LoggerFactory.getLogger(UserJoinCounterService.class);

    private final UserChallengeCounterRepository counterRepository;
    private final MeterRegistry meterRegistry;
    /**
     * 재계산 한 건의 트랜잭션 경계. 애너테이션이 아니라 템플릿인 이유는 {@link #recompute} 가
     * {@link #recomputeOne} 을 같은 빈 안에서 부르기 때문이다 — 자기 호출은 프록시를 타지 않아
     * {@code @Transactional} 이 조용히 무시되고, 그러면 {@code FOR UPDATE} 락이 즉시 풀려버린다.
     */
    private final TransactionTemplate recomputeTx;

    public UserJoinCounterService(UserChallengeCounterRepository counterRepository,
                                  MeterRegistry meterRegistry,
                                  PlatformTransactionManager transactionManager) {
        this.counterRepository = counterRepository;
        this.meterRegistry = meterRegistry;
        this.recomputeTx = new TransactionTemplate(transactionManager);
        this.recomputeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recomputeTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 원천에서 센 슬롯 사용량. <b>호출부 트랜잭션에 합류한다</b>(SUPPORTS) — 자기 트랜잭션의 미커밋 변경이
     * 보여야 하는 곳(가입 게이트 판정, 강퇴 직후 덮어쓰기)은 이걸 쓴다.
     *
     * <p>락을 잡지 않으므로 사용자 행 락을 이미 쥔 상태에서 불러도 새 락이 생기지 않는다.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int countActiveSlots(UUID userId) {
        return counterRepository.countActiveSlots(userId);
    }

    /**
     * 사용자 행을 잠그고 원천에서 다시 세어 덮어쓴다. 값이 실제로 바뀌었으면 1, 이미 맞았으면 0.
     *
     * <p><b>호출 조건: 호출부가 챌린지 행 락을 쥐고 있지 않을 것.</b> 쥔 채로 부르면 락 순서가
     * (챌린지 → 사용자)로 뒤집혀 가입 경로(사용자 → 챌린지)와 데드락 사이클을 만든다.
     * 종료·자동삭제 배치가 굳이 커밋 뒤로 재계산을 미루는 이유가 이것이다.
     *
     * <p><b>항상 새 트랜잭션</b>이라 배치가 사용자 단위로 실패를 격리할 수 있다. 뒤집어 말하면
     * 자기 트랜잭션의 미커밋 변경은 <b>보이지 않는다</b> — 강퇴처럼 "방금 내가 바꾼 상태"를 반영해야 하는 곳에서
     * 이걸 부르면 옛값을 쓴다. 그런 곳은 {@link #countActiveSlots} 로 세서 직접 덮어써야 한다.
     *
     * <p><b>READ_COMMITTED 를 명시하는 이유.</b> 기본 REPEATABLE READ 에서는 "스냅샷이 언제 만들어지는가"에
     * 따라 락을 얻기 직전에 커밋된 가입이 안 보일 수 있다. 그러면 방금 +1 한 값을 옛 카운트로 덮어쓴다.
     * 문장마다 최신 스냅샷을 보장하는 READ_COMMITTED 로 그 미묘함을 없앤다.
     */
    public int recomputeOne(UUID userId, String reason) {
        Integer changed = recomputeTx.execute(tx -> {
            counterRepository.ensureRow(userId);   // 회원가입이 카운터 행을 만들지 않는다 — 없으면 UPDATE 가 0행
            Integer stored = counterRepository.lockCount(userId);
            int truth = counterRepository.countActiveSlots(userId);
            int updated = counterRepository.setCount(userId, truth);
            if (updated > 0) {
                // 재계산은 덮어쓰기라 "왜 그 값이었나"가 사라진다 — before/after 를 남겨야 원인을 좇을 수 있다.
                log.warn("join_counter_fixed userId={} stored={} truth={} reason={}",
                        userId, stored, truth, reason);
                Counter.builder("join_counter_fixed_total")
                        .description("동시 참여 카운터가 원천과 어긋나 교정된 횟수")
                        .tag("reason", reason)
                        .register(meterRegistry)
                        .increment();
            }
            return updated;
        });
        return (changed != null) ? changed : 0;
    }

    /**
     * 여러 사용자를 <b>한 명당 트랜잭션 하나</b>로 재계산한다. 고쳐진 사용자 수 반환.
     *
     * <p>한 트랜잭션에 여러 사용자를 묶으면 재계산끼리 락 획득 순서가 엇갈려 데드락이 날 수 있고
     * 락 유지 시간도 길어진다. 자동 삭제 배치가 "방 하나 = 트랜잭션 하나"로 격리한 것과 같은 결이다.
     * 한 명이 실패해도 나머지는 계속 간다 — 실패분은 보정 배치가 주워 간다.
     */
    public int recompute(Collection<UUID> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int fixed = 0;
        for (UUID userId : userIds) {
            try {
                fixed += recomputeOne(userId, reason);
            } catch (Exception e) {
                Counter.builder("join_counter_recompute_failure_total")
                        .description("동시 참여 카운터 재계산 실패 횟수")
                        .tag("reason", reason)
                        .register(meterRegistry)
                        .increment();
                log.error("참여 카운터 재계산 실패 userId={} reason={}: {}", userId, reason, e.getMessage(), e);
            }
        }
        return fixed;
    }
}
