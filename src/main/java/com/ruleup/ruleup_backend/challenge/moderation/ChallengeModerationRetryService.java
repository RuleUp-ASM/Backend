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
 * 챌린지 이미지 검수 재시도 배치 (CLAUDE.md §5.1 / §8 — AI 미가용 복구).
 *
 * <p>심사는 생성/수정 직후 {@link ChallengeModerationEventListener}(AFTER_COMMIT·@Async)가
 * 즉시 1회 시도한다. 그 시점에 LLM/AI API 가 죽어 있으면({@code UNAVAILABLE}) 항목이 IN_REVIEW 로 남고,
 * 그동안 타인 화면은 대체 표시(AI 임시 제목·빈 설명·기본 이미지)가 유지된다 — 기능 제한은 없다.
 *
 * <p>이 배치가 그 공백을 메운다: 마지막 변경 후 일정 시간이 지나도록 IN_REVIEW 인 항목이 있는 챌린지를
 * 주기적으로 다시 {@link ChallengeModerationService#moderate}에 넣어, AI 가 복구되는 즉시 결론이 나게
 * 한다(스펙의 "일 1회 PENDING 재심사"보다 촘촘한 수렴 — 대체 표시 기간 최소화).
 *
 * 동시성: FOR UPDATE SKIP LOCKED 선점 + moderate() 의 IN_REVIEW 가드라 다중 인스턴스에서도
 *         중복 심사·중복 알림이 없다(활성화/완료 배치와 동일한 DB 멱등 패턴).
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
        List<Challenge> stalled = challengeRepository.findModerationInReviewStalledForUpdate(threshold, CLAIM_LIMIT);
        for (Challenge c : stalled) {
            // 같은 트랜잭션에 합류(REQUIRED) → 선점한 행 잠금이 검수·전이까지 유지된다.
            moderationService.moderate(c.getId());
        }
        if (!stalled.isEmpty()) {
            log.info("모더레이션 재검 배치: 지체된 챌린지 {}건 재검수 시도", stalled.size());
        }
    }
}
