package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>한 실행에서 끝까지 돈다</b>: 확정 배치는 같은 시각으로 여러 건을 닫는다. 워터마크 시각부터 상한만큼만
 * 읽으면 같은 시각이 상한을 넘을 때 매일 그 앞부분만 다시 읽고 멈춘다. 그래서 실행 안에서는
 * (verifiedAt, id) 복합 커서로 쪽을 넘기고, 쪽마다 자기 트랜잭션으로 커밋한다. 중단돼도 커밋된 쪽까지는
 * 워터마크가 올라가 있고, 다음 실행은 그 시각부터 다시 시작해 같은 시각의 나머지를 커서로 넘어간다.
 * 워터마크는 지금처럼 시각 하나로 충분하다 — 경계 시각 재수집은 upsert 가 흡수한다.
 */
@Service
public class RoutineOutcomeCollector {

    private static final Logger log = LoggerFactory.getLogger(RoutineOutcomeCollector.class);

    /** 종결(추천 이력에 남길) 판정만. NOT_TARGET/NOT_REQUIRED/PENDING 제외. */
    private static final Set<VerificationStatus> TERMINAL =
            Set.of(VerificationStatus.SUCCESS, VerificationStatus.FAILED);

    /**
     * 한 실행에서 넘길 쪽 수 상한 — 5,000 × 200 = 100만 행. 무한 루프 방어다. 여기 걸리면 커밋된 쪽까지
     * 워터마크가 올라가 있으므로 다음 실행이 이어받는다.
     */
    private static final int MAX_PAGES = 200;

    private final VerificationDailyRepository dailyRepo;
    private final ChallengeRepository challengeRepo;
    private final RoutineOutcomeRepository outcomeRepo;
    private final TransactionTemplate tx;

    /** 한 쪽 처리량(폭주 방지). 한 쪽이 한 트랜잭션이다. */
    private final int pageSize;

    /** 첫 수집(워터마크 없음) 시 소급할 일수. */
    private final int lookbackDays;

    public RoutineOutcomeCollector(VerificationDailyRepository dailyRepo,
                                   ChallengeRepository challengeRepo,
                                   RoutineOutcomeRepository outcomeRepo,
                                   TransactionTemplate tx,
                                   @Value("${app.recommendation.outcome-page-size:5000}") int pageSize,
                                   @Value("${app.recommendation.outcome-lookback-days:30}") int lookbackDays) {
        this.dailyRepo = dailyRepo;
        this.challengeRepo = challengeRepo;
        this.outcomeRepo = outcomeRepo;
        this.tx = tx;
        this.pageSize = pageSize;
        this.lookbackDays = lookbackDays;
    }

    /** 매일 03:30 KST: 전날까지 확정된 종결행을 아웃컴 이력으로 수집(세그먼트 재집계 04:00 직전). */
    @SchedulerLock(name = "RoutineOutcomeCollector.collect", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void collect() {
        Instant highWater = outcomeRepo.findMaxConfirmedAt();
        Instant since = (highWater != null) ? highWater : Instant.now().minus(Duration.ofDays(lookbackDays));

        Map<UUID, Optional<Challenge>> challengeCache = new HashMap<>();   // 챌린지 1개에 여러 날짜 → 조회 캐시
        Cursor cursor = new Cursor(since, null);
        int processed = 0, inserted = 0, updated = 0;

        for (int round = 0; round < MAX_PAGES; round++) {
            Cursor at = cursor;
            Page page = tx.execute(status -> collectPage(at, challengeCache));
            processed += page.size();
            inserted += page.inserted();
            updated += page.updated();
            // 덜 찼다 = 남은 행이 없다. 비교 대상은 반복 횟수가 아니라 쪽 크기다.
            if (page.size() < pageSize) {
                if (processed > 0) {
                    log.info("루틴 아웃컴 수집: 종결 {}건 처리(신규 {}, 갱신 {}) since={}", processed, inserted, updated, since);
                }
                return;
            }
            cursor = page.next();
        }
        log.warn("루틴 아웃컴 수집 상한 도달 — 종결 {}건 처리(신규 {}, 갱신 {}) since={}. 남은 행은 다음 실행이 이어받는다.",
                processed, inserted, updated, since);
    }

    /** 커서 뒤 한 쪽을 upsert 한다. 호출부가 쪽마다 트랜잭션을 연다. */
    private Page collectPage(Cursor cursor, Map<UUID, Optional<Challenge>> challengeCache) {
        List<VerificationDaily> terminal = dailyRepo.findTerminalAfter(
                TERMINAL, cursor.verifiedAt(), cursor.id(), PageRequest.of(0, pageSize));
        if (terminal.isEmpty()) return new Page(0, 0, 0, cursor);

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
        VerificationDaily last = terminal.getLast();
        return new Page(terminal.size(), inserted, updated, new Cursor(last.getVerifiedAt(), last.getId()));
    }

    /** (확정 시각, id) 복합 커서. id 가 null 이면 그 시각 이상 전부. */
    private record Cursor(Instant verifiedAt, UUID id) {}

    private record Page(int size, int inserted, int updated, Cursor next) {}
}
