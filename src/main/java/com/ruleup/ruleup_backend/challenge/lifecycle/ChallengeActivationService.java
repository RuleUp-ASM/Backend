package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeGridChanged;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.stats.ChallengeStatsRefreshRequested;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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

    private final ChallengeRepository challengeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 1분마다: 시작일이 도달한 시작 전(UPCOMING)·모더레이션 통과 챌린지를 ACTIVE 로 전환한다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void activateDueChallenges() {
        LocalDate today = LocalDate.now(KST);
        List<Challenge> due = challengeRepository.findUpcomingDueForActivationForUpdate(today, CLAIM_LIMIT);
        for (Challenge c : due) {
            c.activate();
            eventPublisher.publishEvent(ChallengeStatsRefreshRequested.of(c.getId(), "CHALLENGE_ACTIVATED"));
        }
        if (!due.isEmpty()) {
            // 그리드는 UPCOMING·ACTIVE 를 함께 세므로 이 전환으로 합계가 바뀌진 않지만,
            // 캐시가 상태를 스냅샷으로 들고 있으므로 전환이 있었으면 버려 최신값으로 맞춘다.
            eventPublisher.publishEvent(ChallengeGridChanged.of("CHALLENGE_ACTIVATED"));
            log.info("시작일 도달로 ACTIVE 전환한 챌린지 {}건", due.size());
        }
    }
}
