package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 챌린지 종료 배치 (CLAUDE.md §5.5/§5.7 — 종료일 도달 시 ACTIVE→COMPLETED).
 *  - endDate(KST)가 지난 ACTIVE 챌린지를 COMPLETED 로 마감한다(endDate 는 마지막 활동일 = 포함).
 *  - lifecycle status 축만 마감. 완주율 집계·매너 정산은 인증(VF)/평판 스펙 소관.
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점이라 다중 인스턴스에서도 중복 전환 없음
 *         ({@link ChallengeActivationService} 와 동일한 ShedLock 없는 DB 멱등 패턴).
 * 인프라(EventBridge/ShedLock)가 갖춰지면 트리거만 교체 — 전환 로직은 그대로(§8).
 */
@Service
@RequiredArgsConstructor
public class ChallengeCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeCompletionService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CLAIM_LIMIT = 200;

    private final ChallengeRepository challengeRepository;

    /** 1분마다: 종료일이 지난 진행중 챌린지를 COMPLETED 로 마감한다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void completeEndedChallenges() {
        LocalDate today = LocalDate.now(KST);
        List<Challenge> due = challengeRepository.findActiveDueForCompletionForUpdate(today, CLAIM_LIMIT);
        for (Challenge c : due) {
            c.complete();
        }
        if (!due.isEmpty()) {
            log.info("종료일 경과로 COMPLETED 전환한 챌린지 {}건", due.size());
        }
    }
}
