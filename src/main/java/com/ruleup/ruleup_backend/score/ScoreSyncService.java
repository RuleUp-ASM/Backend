package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.score.domain.CycleScoreState;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 점수 정산 배치 — 확정된 판정을 사이클 정산으로 밀어 넣고, 끝난 사이클을 마감한다.
 *
 * <p><b>왜 이벤트가 아니라 배치인가.</b> 정책은 "성공 확정 즉시 반영"을 요구하지만 성공에는 별도
 * 도메인 이벤트가 없고 확정 경로가 여러 곳(sync 즉시 · 확정 배치 · 이의 정정)이다. 종결행을 한 곳에서
 * 훑는 편이 누락 없이 단순하다 — 추천 아웃컴 수집({@code RoutineOutcomeCollector})이 같은 이유로
 * 같은 모양을 쓴다. 다만 점수는 사용자에게 바로 보여야 하므로 일배치가 아니라 <b>분 단위</b>로 돈다.
 *
 * <p>워터마크는 별도 테이블이 아니라 {@code cycle_score_states.last_judged_at} 의 최댓값이다.
 * 정산 대상 자체에 "어디까지 봤나"가 적혀 있어 상태가 두 벌로 갈라지지 않는다. 경계행을 다시 훑어도
 * 정산이 멱등이라 안전하다.
 */
@Service
@RequiredArgsConstructor
public class ScoreSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScoreSyncService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 점수 대상이 되는 종결 판정. */
    private static final Set<VerificationStatus> TERMINAL =
            Set.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED);

    /** 한 번에 훑을 판정 수 상한. 밀리면 다음 회차가 이어받는다. */
    private static final int BATCH_LIMIT = 2_000;

    /** 워터마크가 없을 때(최초 가동) 소급할 기간. */
    private static final Duration COLD_START_LOOKBACK = Duration.ofDays(14);

    /**
     * 워터마크를 조금 뒤로 물려 다시 훑는 폭. 같은 초에 확정된 판정이 배치 상한에 걸려 반쯤 잘리면
     * 다음 회차가 그 초를 통째로 건너뛸 수 있다. 정산이 멱등이라 겹쳐 읽는 쪽이 안전하다.
     */
    private static final Duration OVERLAP = Duration.ofMinutes(5);

    private final VerificationDailyRepository dailyRepository;
    private final CycleScoreStateRepository cycleRepository;
    private final ChallengeRepository challengeRepository;
    private final ScoreService scoreService;

    /**
     * 확정된 판정을 사이클 정산에 반영한다.
     *
     * <p>정산 자체는 {@link ScoreService#reconcileCycle} 이 사용자 단위 트랜잭션으로 처리한다.
     * 여기서는 <b>어느 사이클을 다시 세야 하는지</b>만 고르므로 이 메서드에 트랜잭션을 두지 않는다 —
     * 한 사용자의 실패가 배치 전체를 되돌리면 안 되기 때문이다.
     */
    @Scheduled(fixedDelay = 60_000)
    public void syncConfirmedJudgements() {
        Instant since = watermark();
        List<VerificationDaily> confirmed = dailyRepository
                .findTerminalSince(TERMINAL, since, PageRequest.of(0, BATCH_LIMIT));
        if (confirmed.isEmpty()) return;

        Set<CycleRef> targets = new LinkedHashSet<>();
        for (VerificationDaily d : confirmed) {
            cycleNoOf(d).ifPresent(no -> targets.add(new CycleRef(d.getUserId(), d.getChallengeId(), no)));
        }
        int failed = 0;
        for (CycleRef ref : targets) {
            try {
                scoreService.reconcileCycle(ref.userId(), ref.challengeId(), ref.cycleNo());
            } catch (RuntimeException e) {
                // 한 사용자의 실패가 나머지를 막지 않는다. 워터마크가 전진하지 않으므로 다음 회차가 다시 집는다.
                failed++;
                log.warn("점수 정산 실패: user={} challenge={} cycle={}",
                        ref.userId(), ref.challengeId(), ref.cycleNo(), e);
            }
        }
        log.info("점수 정산: 확정 {}건 → 사이클 {}개 정산(실패 {}) since={}",
                confirmed.size(), targets.size(), failed, since);
    }

    /**
     * 끝난 사이클을 마감한다 — 3단계 판정과 연속 기록·보너스가 여기서 붙는다.
     *
     * <p>매일 03:10 KST. 인증 확정 경계(귀속일+2일 00:00 KST)를 넉넉히 지난 뒤라야 마지막 날의
     * 판정이 다 들어와 있다. 마감은 멱등이라 밀려도 다음 날이 이어받는다.
     */
    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
    public void closeFinishedCycles() {
        // 사이클 7일이 끝나고, 마지막 날의 실패 확정(귀속일+2일)까지 지난 것만 닫는다.
        LocalDate cutoff = LocalDate.now(KST).minusDays(ChallengeCycle.CYCLE_DAYS + 2L);
        List<CycleScoreState> closable = cycleRepository.findClosable(cutoff);
        int closed = 0;
        for (CycleScoreState c : closable) {
            try {
                scoreService.closeCycle(c.getUserId(), c.getChallengeId(), c.getCycleNo());
                closed++;
            } catch (RuntimeException e) {
                log.warn("사이클 마감 실패: user={} challenge={} cycle={}",
                        c.getUserId(), c.getChallengeId(), c.getCycleNo(), e);
            }
        }
        if (closed > 0) log.info("사이클 마감: {}건", closed);
    }

    /** 고수위 워터마크 — 정산 대상 자체에 적혀 있다. 없으면(최초 가동) 소급 기간만큼 뒤로 간다. */
    private Instant watermark() {
        Instant high = cycleRepository.findMaxLastJudgedAt();
        return (high != null) ? high.minus(OVERLAP) : Instant.now().minus(COLD_START_LOOKBACK);
    }

    /**
     * 판정이 속한 사이클 회차. 시작일 이전 판정(중간 입장 전 등)은 정산 대상이 아니다.
     * 사이클은 테이블이 아니라 시작일로부터의 주 단위 계산이라 여기서 나눗셈으로 구한다.
     */
    private Optional<Integer> cycleNoOf(VerificationDaily daily) {
        Challenge challenge = challengeRepository.findById(daily.getChallengeId()).orElse(null);
        if (challenge == null || challenge.getStartDate() == null) return Optional.empty();
        long elapsed = ChronoUnit.DAYS.between(challenge.getStartDate(), daily.getTargetDate());
        if (elapsed < 0) return Optional.empty();
        return Optional.of((int) (elapsed / ChallengeCycle.CYCLE_DAYS) + 1);
    }

    /** 다시 세야 할 사이클. 같은 사이클에 여러 판정이 들어와도 한 번만 정산한다. */
    private record CycleRef(UUID userId, UUID challengeId, int cycleNo) {}
}
