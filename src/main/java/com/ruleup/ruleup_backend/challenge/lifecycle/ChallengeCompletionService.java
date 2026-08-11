package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.counter.UserJoinCounterService;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeGridChanged;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 챌린지 종료 배치 (CLAUDE.md §5.5/§5.7 — 종료일 도달 시 ACTIVE→COMPLETED).
 *  - endDate(KST)가 지난 ACTIVE 챌린지를 COMPLETED 로 마감한다(endDate 는 마지막 활동일 = 포함).
 *  - lifecycle status 축만 마감. 완주율 집계·매너 정산은 인증(VF)/평판 스펙 소관.
 *  - 마감된 방의 참여자는 <b>동시 참여 슬롯을 돌려받는다</b>(아래 참조).
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
    private final ChallengeMemberRepository memberRepository;
    private final UserJoinCounterService joinCounterService;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /** 1분마다: 종료일이 지난 진행중 챌린지를 COMPLETED 로 마감한다. */
    @Scheduled(fixedDelay = 60_000)
    public void completeEndedChallenges() {
        // 트랜잭션 경계를 명시적으로 좁힌다. 이 안에서는 challenges 행 락을 쥐고 있으므로
        // 사용자 카운터를 건드리면 락 순서가 (챌린지 → 사용자)로 뒤집혀 가입 경로와 데드락이 난다.
        // 그래서 여기서는 "누구의 슬롯을 돌려줘야 하는지"만 모으고, 실제 재계산은 커밋 뒤에 한다.
        Set<UUID> affectedUsers = transactionTemplate.execute(tx -> completeDueAndCollectMembers());
        if (affectedUsers == null || affectedUsers.isEmpty()) return;

        // 종료된 방은 슬롯 계산에서 빠진다. 멤버 행은 ACTIVE 그대로 두고 카운터만 줄인다 —
        // 완주 기록·최종 랭킹의 근거가 멤버 행이라 지우면 안 되기 때문이다.
        int released = joinCounterService.recompute(affectedUsers, "CHALLENGE_COMPLETED");
        log.info("종료 배치 슬롯 회수: 대상 {}명 중 {}명 카운터 조정", affectedUsers.size(), released);
    }

    /** 종료 전환 + 마감된 방의 현재 멤버 수집(같은 트랜잭션). 슬롯을 돌려줄 사용자 집합을 반환. */
    private Set<UUID> completeDueAndCollectMembers() {
        LocalDate today = LocalDate.now(KST);
        List<Challenge> due = challengeRepository.findActiveDueForCompletionForUpdate(today, CLAIM_LIMIT);
        if (due.isEmpty()) return Set.of();

        Set<UUID> members = new LinkedHashSet<>();   // 여러 방이 동시에 끝난 사용자는 한 번만 재계산하면 된다
        for (Challenge c : due) {
            c.complete();
            for (ChallengeMember m : memberRepository
                    .findByChallengeIdAndStatusOrderByJoinedAtAsc(c.getId(), MemberStatus.ACTIVE)) {
                members.add(m.getUserId());
            }
        }
        // 그리드는 UPCOMING·ACTIVE 를 세므로 종료된 방은 집계에서 빠진다 → 캐시를 버려야 수가 따라 내려간다.
        eventPublisher.publishEvent(ChallengeGridChanged.of("CHALLENGE_COMPLETED"));
        log.info("종료일 경과로 COMPLETED 전환한 챌린지 {}건", due.size());
        return members;
    }
}
