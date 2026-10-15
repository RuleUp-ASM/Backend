package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeGridChanged;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 챌린지 활성화 배치 (생성 및 라이프사이클 §2 — 시작일 도달 시 UPCOMING→ACTIVE).
 *  - startDate(KST)가 도달한 모더레이션 통과(NONE/APPROVED)·UPCOMING 챌린지를 ACTIVE 로 전환한다.
 *  - ACTIVE 가 되어야 인증 sync(§3.1)가 그 챌린지를 평가 대상으로 삼는다(VerificationSyncService §11.3).
 *  - 모더레이션 미통과(PENDING_REVIEW/REJECTED) 챌린지는 가입·노출이 막혀 있어 활성화 대상이 아니다.
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 중복 전환 없음
 *         (ChallengeModerationCloseService 와 동일한 ShedLock 없는 DB 멱등 패턴).
 * 인프라(EventBridge/ShedLock)가 갖춰지면 트리거만 교체 — 전환 로직은 그대로(§8).
 */
@Service
@RequiredArgsConstructor
public class ChallengeActivationService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeActivationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CLAIM_LIMIT = 200;
    /** 한 번 실행에서 쓰는 최대 시간 — 락(lockAtMostFor 10분) 안에서 끝나야 다른 태스크가 겹쳐 잡지 않는다. */
    private static final java.time.Duration RUN_BUDGET = java.time.Duration.ofMinutes(4);

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 1분마다: 시작일이 도달한 시작 전(UPCOMING)·모더레이션 통과 챌린지를 ACTIVE 로 전환한다.
     *
     * <p><b>남은 것이 없을 때까지</b> 200건씩 끊어 돈다(한 덩어리 = 한 트랜잭션). 예전엔 한 번에 200건만 하고 끝나
     * 1분에 200건이 상한이었다 — 생성 기본값이 「내일 시작」이라 시작일이 자정에 몰리는데, 부하테스트(2026-10)에서
     * 4,870건이 다 ACTIVE 가 되기까지 22분 걸렸다. 그동안 그 방들은 오늘 시작인데 「시작 전」이고 인증도 평가되지 않는다.
     * 덩어리마다 트랜잭션을 끊어 잠금·커밋을 짧게 두고, 실행 한 번은 {@link #RUN_BUDGET} 안에서 멈춘다.
     */
    @SchedulerLock(name = "ChallengeActivationService.activateDueChallenges", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelay = 60_000)
    public void activateDueChallenges() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        java.time.Instant deadline = java.time.Instant.now().plus(RUN_BUDGET);
        int total = 0;
        while (true) {
            Integer done = tx.execute(status -> activateChunk());
            total += (done == null ? 0 : done);
            if (done == null || done < CLAIM_LIMIT || java.time.Instant.now().isAfter(deadline)) break;
        }
        if (total > CLAIM_LIMIT) log.info("시작일 도달 전환 합계 {}건(한 번 실행)", total);
    }

    private int activateChunk() {
        LocalDate today = LocalDate.now(KST);
        List<Challenge> due = challengeRepository.findUpcomingDueForActivationForUpdate(today, CLAIM_LIMIT);
        for (Challenge c : due) {
            c.activate();
            notifyLifecycle(c, "STARTED");
            eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(c.getId(), "CHALLENGE_ACTIVATED"));
        }
        if (!due.isEmpty()) {
            // 그리드는 UPCOMING·ACTIVE 를 함께 세므로 이 전환으로 합계가 바뀌진 않지만,
            // 캐시가 상태를 스냅샷으로 들고 있으므로 전환이 있었으면 버려 최신값으로 맞춘다.
            eventPublisher.publishEvent(ChallengeGridChanged.of("CHALLENGE_ACTIVATED"));
            log.info("시작일 도달로 ACTIVE 전환한 챌린지 {}건", due.size());
        }
        return due.size();
    }

    /**
     * 그 방의 현재 멤버 전원에게 생명주기 고지. <b>묶음 발행</b>이라 SQS 호출이 100건에 한 번이고,
     * {@code dedup_key = CHALLENGE_LIFECYCLE:{user}:{challenge}:{phase}} 가 재실행 중복을 막는다.
     */
    private void notifyLifecycle(Challenge c, String phase) {
        List<ChallengeMember> members = memberRepository
                .findByChallengeIdAndStatusOrderByJoinedAtAsc(c.getId(), MemberStatus.ACTIVE);
        if (members.isEmpty()) return;

        notificationPublisher.publishAll(members.stream()
                .map(m -> NotificationEvent.forChallenge(m.getUserId(),
                        NotificationType.CHALLENGE_LIFECYCLE, c.getId(),
                        Map.of(NotificationParams.CHALLENGE_ID, c.getId().toString(),
                                NotificationParams.PHASE, phase)))
                .toList());
    }
}
