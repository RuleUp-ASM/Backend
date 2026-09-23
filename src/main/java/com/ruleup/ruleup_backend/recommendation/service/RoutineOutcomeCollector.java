package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 루틴 아웃컴 수집 배치 — 추천 학습용 이력 적재.
 *
 * <p>목적: "성공한 사람 루틴을 비슷한 카테고리의 실패한 사람에게 추천"을 위한 원천 데이터 축적.
 * verification 이 확정한 하루 판정(SUCCESS/FAILED)을 사용자·템플릿·카테고리와 함께 {@link RoutineOutcome} 로 쌓는다.
 * 지금은 <b>수집만</b> 한다(추천 쿼리/엔드포인트는 데이터가 쌓인 뒤 별도 단계).
 *
 * <p>실시간 이벤트 대신 <b>일배치</b>로 모은 이유: 성공에는 별도 도메인 이벤트가 없고(실패만 존재),
 * 확정 경로가 여러 곳(sync 즉시·확정 배치·방장 승인)이라 종결행({@code VerificationDaily.status IN (SUCCESS,FAILED)})을
 * 한 곳에서 훑는 편이 누락 없이 단순하다. SegmentScoreService(추천 배치)와 같은 방식.
 *
 * <p><b>멱등</b>: 고수위 워터마크(마지막 confirmedAt) 이후를 {@code >=} 로 다시 훑되,
 * uq(challengeId,userId,targetDate)로 upsert 하므로 경계행 재수집·PENDING→확정 정정이 안전하다.
 * segment 재집계(04:00) 직전인 03:30 에 돌려 그날치가 반영되게 한다.
 */
@Service
@RequiredArgsConstructor
public class RoutineOutcomeCollector {

    private static final Logger log = LoggerFactory.getLogger(RoutineOutcomeCollector.class);

    /** 종결(추천 이력에 남길) 판정만. NOT_TARGET/NOT_REQUIRED/PENDING 제외. */
    private static final Set<VerificationStatus> TERMINAL =
            Set.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED);
    /** 한 배치 처리 상한(폭주 방지). 밀리면 다음 배치가 이어받는다. */
    private static final int BATCH_LIMIT = 5000;

    private final VerificationDailyRepository dailyRepo;
    private final ChallengeRepository challengeRepo;
    private final RoutineOutcomeRepository outcomeRepo;

    /** 첫 수집(워터마크 없음) 시 소급할 일수. */
    @Value("${app.recommendation.outcome-lookback-days:30}")
    private int lookbackDays;

    /** 매일 03:30 KST: 전날까지 확정된 종결행을 아웃컴 이력으로 수집(세그먼트 재집계 04:00 직전). */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @Transactional
    public void collect() {
        Instant highWater = outcomeRepo.findMaxConfirmedAt();
        Instant since = (highWater != null) ? highWater : Instant.now().minus(Duration.ofDays(lookbackDays));

        List<VerificationDaily> terminal =
                dailyRepo.findTerminalSince(TERMINAL, since, PageRequest.of(0, BATCH_LIMIT));
        if (terminal.isEmpty()) return;

        Map<UUID, Optional<Challenge>> challengeCache = new HashMap<>();   // 챌린지 1개에 여러 날짜 → 조회 캐시
        int inserted = 0, updated = 0;

        for (VerificationDaily d : terminal) {
            Challenge ch = challengeCache
                    .computeIfAbsent(d.getChallengeId(), challengeRepo::findById)
                    .orElse(null);
            Long templateId = (ch != null) ? ch.getTemplateId() : null;
            String category = (ch != null) ? ch.getCategory() : null;

            Optional<RoutineOutcome> existing = outcomeRepo
                    .findByChallengeIdAndUserIdAndTargetDate(d.getChallengeId(), d.getUserId(), d.getTargetDate());
            if (existing.isPresent()) {
                existing.get().update(d.getStatus(), d.getVerifiedVia(), d.getFailureReason(), d.getVerifiedAt());
                updated++;
            } else {
                outcomeRepo.save(RoutineOutcome.record(
                        d.getUserId(), d.getChallengeId(), d.getChallengeMemberId(),
                        templateId, category, d.getTargetDate(),
                        d.getStatus(), d.getVerifiedVia(), d.getFailureReason(), d.getVerifiedAt()));
                inserted++;
            }
        }
        log.info("루틴 아웃컴 수집: 종결 {}건 처리(신규 {}, 갱신 {}) since={}", terminal.size(), inserted, updated, since);
    }
}
