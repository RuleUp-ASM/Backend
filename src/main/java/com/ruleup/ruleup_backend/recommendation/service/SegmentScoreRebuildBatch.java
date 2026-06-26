package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScoreId;
import com.ruleup.ruleup_backend.recommendation.dto.ChallengeCreatorSegmentRow;
import com.ruleup.ruleup_backend.recommendation.repository.ChallengeSegmentScoreQueryRepository;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 세그먼트 인기 점수 윈도우 재계산 배치(추천 warm-up).
 *
 * <p>실시간 증분(생성마다 +1 upsert) 대신 <b>주기 배치 + 슬라이딩 윈도우</b> 방식:
 * <ul>
 *   <li>원천은 Challenge 테이블(별도 이벤트 저장소 불필요). 최근 {@code windowDays}일 안에 생성된
 *       챌린지를 생성자 세그먼트(COUNTRY·GENDER·AGE_BAND)별로 카운트해 점수를 통째로 교체한다.</li>
 *   <li>점수가 "배치 시점에만" 바뀌므로 캐시 무효화 thrash가 없고, 오래된 인기 고착 없이
 *       최신성(window)이 자동 반영된다.</li>
 *   <li>delete + save를 한 트랜잭션으로 묶어 원자 교체(읽는 쪽은 교체 전/후만 본다).</li>
 *   <li>캐시 무효화는 <b>커밋 이후</b>에만(afterCommit) — 커밋 전 무효화 시 미커밋 데이터로
 *       재캐싱되는 레이스를 막는다.</li>
 * </ul>
 *
 * <p>스케일 메모: 단일 인스턴스 전제. 다중 인스턴스로 가면 중복 재계산을 막도록 ShedLock 등을 위에 얹는다
 * (전체 교체라 중복 실행이 결과를 깨진 않지만 불필요한 부하).
 */
@Service
@RequiredArgsConstructor
public class SegmentScoreRebuildBatch {

    private static final Logger log = LoggerFactory.getLogger(SegmentScoreRebuildBatch.class);

    private final ChallengeSegmentScoreQueryRepository challengeQueryRepo;
    private final TemplateSegmentScoreRepository scoreRepo;
    private final SegmentResolver segmentResolver;
    private final SegmentScoreReader segmentScoreReader;

    @Value("${app.recommendation.segment-window-days:30}")
    private int windowDays;

    /**
     * 부팅 후 initialDelay 뒤 1회 + 이후 fixedDelay 주기로 재계산.
     * (기본 1시간. 추천은 실시간성이 필요 없어 이 정도 지연은 무해.)
     */
    @Scheduled(
            initialDelayString = "${app.recommendation.rebuild-initial-delay-ms:60000}",
            fixedDelayString = "${app.recommendation.rebuild-interval-ms:3600000}")
    @Transactional
    public void rebuild() {
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        List<ChallengeCreatorSegmentRow> rows = challengeQueryRepo.findCreatorSegmentRowsSince(since);

        // (세그먼트축, 값, templateId) → 선택 횟수
        Map<TemplateSegmentScoreId, Integer> counts = new HashMap<>();
        for (ChallengeCreatorSegmentRow row : rows) {
            for (Segment seg : segmentResolver.resolve(row.countryCode(), row.gender(), row.birthDate())) {
                counts.merge(new TemplateSegmentScoreId(seg.type(), seg.value(), row.templateId()), 1, Integer::sum);
            }
        }

        List<TemplateSegmentScore> rebuilt = counts.entrySet().stream()
                .map(e -> TemplateSegmentScore.ofAggregate(
                        e.getKey().getSegmentType(),
                        e.getKey().getSegmentValue(),
                        e.getKey().getTemplateId(),
                        BigDecimal.valueOf(e.getValue()),   // 점수 = 윈도우 내 선택 횟수(가중치 1)
                        e.getValue()))
                .toList();

        scoreRepo.deleteAllInBatch();
        scoreRepo.saveAll(rebuilt);

        // 커밋된 뒤에만 캐시 무효화 → 다음 조회가 최신 점수로 다시 채워진다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                segmentScoreReader.evictAll();
            }
        });

        log.info("[SegmentScoreRebuild] window={}d challenges={} scoreRows={}",
                windowDays, rows.size(), rebuilt.size());
    }
}
