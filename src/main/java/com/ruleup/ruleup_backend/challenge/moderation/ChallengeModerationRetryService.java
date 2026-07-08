package com.ruleup.ruleup_backend.challenge.moderation;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 챌린지 이름 검수 재시도 배치 (CLAUDE.md §5.1 / §8 — AI 미가용 복구).
 *
 * <p>이름 검수는 생성/이름변경 직후 {@link ChallengeModerationEventListener}(AFTER_COMMIT·@Async)가
 * 즉시 1회 시도한다. 그런데 그 시점에 LLM/AI API 가 죽어 있으면({@code UNAVAILABLE}) 챌린지는
 * PENDING_REVIEW 로 남고 — 이 상태는 타인에게 비노출·가입 불가라 — 방치하면 영원히 공개되지 않는다.
 *
 * <p>이 배치가 그 공백을 메운다: 마지막 변경 후 일정 시간이 지나도록 PENDING_REVIEW 인 챌린지를
 * 주기적으로 다시 {@link ChallengeModerationService#moderate}에 넣어, AI 가 복구되는 즉시 결론이 나게 한다.
 * (SQS 재처리 큐의 인프로세스 대체 — 인프라가 갖춰지면 트리거만 SQS consumer 로 교체, §8.)
 *
 * <ul>
 *   <li>AI 가 한 번이라도 결론(APPROVED/REJECTED)을 내면 PENDING_REVIEW 를 벗어나 대상에서 빠진다
 *       → "AI 를 거친 뒤엔 재검하지 않는다"는 요구를 만족.</li>
 *   <li>정상 처리분(방금 생성돼 리스너가 곧 처리)과 겹치지 않도록 {@link #STALL_THRESHOLD} 이상
 *       지체된 것만 집는다.</li>
 * </ul>
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점 + moderate() 의 PENDING_REVIEW 가드라 다중 인스턴스에서도
 *         중복 검수·중복 알림이 없다(활성화/마감 배치와 동일한 DB 멱등 패턴).
 */
@Service
@RequiredArgsConstructor
public class ChallengeModerationRetryService {

    private static final Logger log = LoggerFactory.getLogger(ChallengeModerationRetryService.class);

    /** 이 시간 이상 PENDING_REVIEW 로 지체된 것만 재검(방금 생성돼 리스너가 처리 중인 건 제외). */
    private static final Duration STALL_THRESHOLD = Duration.ofMinutes(2);
    private static final int CLAIM_LIMIT = 50;

    private final ChallengeRepository challengeRepository;
    private final ChallengeModerationService moderationService;

    /** 1분마다: AI 미가용 등으로 검수가 지체된 챌린지를 다시 검수한다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void retryStalledModeration() {
        Instant threshold = Instant.now().minus(STALL_THRESHOLD);
        List<Challenge> stalled = challengeRepository.findPendingModerationStalledForUpdate(threshold, CLAIM_LIMIT);
        for (Challenge c : stalled) {
            // 같은 트랜잭션에 합류(REQUIRED) → 선점한 행 잠금이 검수·전이까지 유지된다.
            moderationService.moderate(c.getId());
        }
        if (!stalled.isEmpty()) {
            log.info("모더레이션 재검 배치: 지체된 챌린지 {}건 재검수 시도", stalled.size());
        }
    }
}
