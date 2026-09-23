package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.explore.ChallengeGridChanged;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.notification.NotificationEvent;
import com.ruleup.ruleup_backend.notification.service.NotificationMuteCleaner;
import com.ruleup.ruleup_backend.notification.service.NotificationPublisher;
import com.ruleup.ruleup_backend.notification.domain.NotificationParams;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final ChallengeMemberRepository memberRepository;
    private final NotificationMuteCleaner muteCleaner;
    private final NotificationPublisher notificationPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /** 1분마다: 종료일이 지난 진행중 챌린지를 COMPLETED 로 마감한다. */
    @SchedulerLock(name = "ChallengeCompletionService.completeEndedChallenges", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelay = 60_000)
    public void completeEndedChallenges() {
        transactionTemplate.execute(tx -> completeDue());
    }

    /** 종료일이 지난 방을 COMPLETED 로 넘기고, 그 방에 걸린 후속 처리를 함께 한다. */
    private int completeDue() {
        LocalDate today = LocalDate.now(KST);
        List<Challenge> due = challengeRepository.findActiveDueForCompletionForUpdate(today, CLAIM_LIMIT);
        if (due.isEmpty()) return 0;

        for (Challenge c : due) {
            c.complete();
            // 종료된 방은 탐색 후보에서 <b>즉시</b> 빠져야 한다. 5분 보정의 유령 제거만 믿으면 그
            // 사이 정렬 ZSET 과 후보 집합에 남아, 목록이 끝난 방을 걸러 내느라 더 읽고(overfetch)
            // 필터가 선택적일 때는 스캔 상한에 걸려 503 까지 난다. 커밋 뒤에 발행된다.
            eventPublisher.publishEvent(
                    new com.ruleup.ruleup_backend.challenge.explore.ChallengeExploreProjectionRequested(c.getId()));
            // 끝난 방의 음소거는 의미를 잃는다. 남겨 두면 설정 목록에 영영 쌓인다.
            muteCleaner.clearMutesOfChallenge(c.getId());

            List<ChallengeMember> active = memberRepository
                    .findByChallengeIdAndStatusOrderByJoinedAtAsc(c.getId(), MemberStatus.ACTIVE);

            // 종료 고지 — 묶음 발행이라 SQS 호출이 100건에 한 번이고,
            // dedup_key = CHALLENGE_LIFECYCLE:{user}:{challenge}:ENDED 가 재실행 중복을 막는다.
            if (!active.isEmpty()) {
                notificationPublisher.publishAll(active.stream()
                        .map(m -> NotificationEvent.forChallenge(m.getUserId(),
                                NotificationType.CHALLENGE_LIFECYCLE, c.getId(),
                                Map.of(NotificationParams.CHALLENGE_ID, c.getId().toString(),
                                        NotificationParams.PHASE, "ENDED")))
                        .toList());
            }
        }
        // 그리드는 UPCOMING·ACTIVE 를 세므로 종료된 방은 집계에서 빠진다 → 캐시를 버려야 수가 따라 내려간다.
        eventPublisher.publishEvent(ChallengeGridChanged.of("CHALLENGE_COMPLETED"));
        log.info("종료일 경과로 COMPLETED 전환한 챌린지 {}건", due.size());
        return due.size();
    }
}
