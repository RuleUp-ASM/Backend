package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentTypeWeight;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.repository.SegmentTypeWeightRepository;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 추천 세그먼트 점수 배치 재집계.
 *
 * <p>챌린지 생성 때마다 즉시 점수를 갱신하면 추천 결과가 매번 바뀌어 캐시 효율이 떨어진다.
 * 그래서 점수는 실시간이 아니라 주기적으로(기본: 매일 04:00 KST) 최근 {@code windowDays}일 안에
 * 생성된 챌린지를 생성자 세그먼트별로 다시 집계해 TemplateSegmentScore 를 통째로 재작성한다(idempotent).
 *
 * <p><b>슬라이딩 윈도우</b>: 전체 누적이 아니라 최근 N일만 집계하므로 오래된 인기 고착 없이 최신성이
 * 자동 반영된다(예전 유행은 윈도우 밖으로 빠지며 자연 감쇠).
 *
 * <p>집계 단위: (segmentType, segmentValue, templateId) → 선택 수.
 * 세그먼트 축은 {@link SegmentResolver} 가 내보내는 그대로(GLOBAL·COUNTRY·GENDER·AGE_BAND·PLATFORM).
 * GLOBAL 은 모든 유저가 공유하므로, 인구통계가 전혀 없는 유저도 '전체 인기도'로 추천받는다.
 *
 * <p><b>캐시 정합성</b>: 점수는 이 배치 시점에만 바뀐다. 재작성 트랜잭션이 <i>커밋된 뒤에만</i>
 * {@link SegmentScoreReader} 캐시를 무효화한다(커밋 전 무효화 시 미커밋 데이터로 재캐싱되는 레이스 방지).
 * 변경 시점 = 무효화 시점이라 캐시 thrash가 없다.
 *
 * <p>현재는 윈도우 전량 재집계(단순·정확). 데이터가 커지면 증분(마지막 실행 이후 챌린지만)으로 최적화 가능.
 */
@Service
@RequiredArgsConstructor
public class SegmentScoreService {

    private static final Logger log = LoggerFactory.getLogger(SegmentScoreService.class);

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final SegmentResolver segmentResolver;
    private final TemplateSegmentScoreRepository scoreRepository;
    private final SegmentTypeWeightRepository weightRepository;
    private final SegmentScoreReader segmentScoreReader;
    private final SegmentTypeWeightReader segmentTypeWeightReader;

    /** 학습 대상 축(GLOBAL 은 자기비교라 발산 0 → 학습 제외, 0.3 고정). */
    private static final List<SegmentType> LEARNABLE_TYPES =
            List.of(SegmentType.COUNTRY, SegmentType.GENDER, SegmentType.AGE_BAND, SegmentType.PLATFORM);

    /** 집계 윈도우(일). 이 기간 안에 생성된 챌린지만 인기도에 반영. */
    @Value("${app.recommendation.segment-window-days:30}")
    private int windowDays;

    /** 매일 04:00 KST: 최근 windowDays일 챌린지로 세그먼트 점수 전면 재집계. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void rebuild() {
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        log.info("세그먼트 점수 재집계 시작(윈도우 {}일, since={})", windowDays, since);

        Map<Key, Long> counts = new HashMap<>();
        Map<UUID, Optional<User>> userCache = new HashMap<>();   // 생성자 1명이 여러 챌린지 → 조회 캐시

        for (Challenge c : challengeRepository.findAll()) {
            if (c.getDeletedAt() != null || c.getTemplateId() == null) continue;   // 직접 입력/삭제분 제외
            if (c.getCreatedAt() != null && c.getCreatedAt().isBefore(since)) continue;   // 윈도우 밖 제외
            User creator = userCache.computeIfAbsent(c.getCreatorId(), userRepository::findById).orElse(null);
            if (creator == null) continue;
            for (Segment seg : segmentResolver.resolve(creator)) {
                counts.merge(new Key(seg.type(), seg.value(), c.getTemplateId()), 1L, Long::sum);
            }
        }

        // 전량 재작성: 기존 행을 비우고 집계 결과로 다시 채운다(이전 데이터와 무관하게 현 상태 반영).
        scoreRepository.deleteAllInBatch();
        List<TemplateSegmentScore> rows = new ArrayList<>(counts.size());
        for (Map.Entry<Key, Long> e : counts.entrySet()) {
            Key k = e.getKey();
            rows.add(TemplateSegmentScore.rebuilt(k.type(), k.value(), k.templateId(), e.getValue()));
        }
        scoreRepository.saveAll(rows);
        log.info("세그먼트 점수 재집계 완료: {}개 (segment,template) 행 재작성", rows.size());

        // §8.1 특성 가중치 학습 — 같은 트랜잭션에서 함께 재작성(점수·가중치 정합 자동 보장).
        List<SegmentTypeWeight> weights = learnWeights(counts);
        weightRepository.deleteAllInBatch();
        weightRepository.saveAll(weights);
        log.info("특성 가중치 학습 완료: {}", weights.stream()
                .map(w -> w.getSegmentType() + "=" + w.getWeight()).toList());

        // 커밋된 뒤에만 캐시 무효화 → 다음 조회가 최신 점수·가중치로 다시 채워진다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                segmentScoreReader.evictAll();
                segmentTypeWeightReader.evictAll();
            }
        });
    }

    // ===== §8.1 특성 가중치 학습 =====

    /**
     * 축 T로 나눴을 때 선호 분포가 전체 평균(GLOBAL)과 얼마나 달라지는지(구별력)를 재 가중치로 변환.
     *  1) 전체 분포 Q(=GLOBAL) 대비 각 값 v의 분포 P_{T,v} 의 Jensen-Shannon 발산(0~1)을 인구수 가중 평균 → D(T).
     *  2) D(T)를 축들 사이에서 정규화해 w_data ∈ [0.5, 1.5] 로 매핑(구별력 큰 축이 큰 값).
     *  3) shrinkage: w(T)=α·w_data+(1−α)·prior, α=n/(n+K) → 표본 얇으면 prior 회귀.
     *  4) clamp [0.2, 2.0]. GLOBAL 은 학습 제외 0.3 고정.
     */
    private List<SegmentTypeWeight> learnWeights(Map<Key, Long> counts) {
        Map<Long, Double> globalDist = normalize(rawCountsByTemplate(counts, SegmentType.GLOBAL));
        long globalTotal = totalCount(counts, SegmentType.GLOBAL);

        // 축별 구별력 D(T) + 표본 n(T)
        Map<SegmentType, Double> dByType = new EnumMap<>(SegmentType.class);
        Map<SegmentType, Long> nByType = new EnumMap<>(SegmentType.class);
        for (SegmentType t : LEARNABLE_TYPES) {
            Map<String, Map<Long, Long>> byValue = countsByValueAndTemplate(counts, t);
            double weightedJsd = 0.0;
            long nT = 0L;
            for (Map<Long, Long> templateCounts : byValue.values()) {
                long nv = templateCounts.values().stream().mapToLong(Long::longValue).sum();
                if (nv == 0) continue;
                double jsd = jensenShannon(normalize(templateCounts), globalDist);
                weightedJsd += jsd * nv;
                nT += nv;
            }
            dByType.put(t, (nT > 0) ? weightedJsd / nT : 0.0);
            nByType.put(t, nT);
        }

        // D(T)를 축들 사이에서 min-max 정규화(구별력 큰 축 → 큰 w_data)
        double dMin = dByType.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double dMax = dByType.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double range = dMax - dMin;

        List<SegmentTypeWeight> out = new ArrayList<>(LEARNABLE_TYPES.size() + 1);
        for (SegmentType t : LEARNABLE_TYPES) {
            double norm = (range > 0) ? (dByType.get(t) - dMin) / range : 0.5;   // 모두 동일하면 중앙(1.0)
            double wData = SegmentWeightPolicy.WDATA_MIN
                    + norm * (SegmentWeightPolicy.WDATA_MAX - SegmentWeightPolicy.WDATA_MIN);
            long nT = nByType.get(t);
            double alpha = (double) nT / (nT + SegmentWeightPolicy.SHRINKAGE_K);
            double w = alpha * wData + (1 - alpha) * SegmentWeightPolicy.prior(t);
            out.add(SegmentTypeWeight.learned(t, SegmentWeightPolicy.clamp(w), nT));
        }
        // GLOBAL: 학습 제외, 고정 0.3
        out.add(SegmentTypeWeight.learned(SegmentType.GLOBAL, SegmentWeightPolicy.GLOBAL_FIXED, globalTotal));
        return out;
    }

    /** 축 t의 (value → (templateId → count)). */
    private Map<String, Map<Long, Long>> countsByValueAndTemplate(Map<Key, Long> counts, SegmentType t) {
        Map<String, Map<Long, Long>> byValue = new HashMap<>();
        for (Map.Entry<Key, Long> e : counts.entrySet()) {
            if (e.getKey().type() != t) continue;
            byValue.computeIfAbsent(e.getKey().value(), k -> new HashMap<>())
                    .merge(e.getKey().templateId(), e.getValue(), Long::sum);
        }
        return byValue;
    }

    /** 축 t 전체의 templateId → count(값 구분 없이 합). GLOBAL 은 값이 ALL 하나뿐. */
    private Map<Long, Long> rawCountsByTemplate(Map<Key, Long> counts, SegmentType t) {
        Map<Long, Long> byTemplate = new HashMap<>();
        for (Map.Entry<Key, Long> e : counts.entrySet()) {
            if (e.getKey().type() != t) continue;
            byTemplate.merge(e.getKey().templateId(), e.getValue(), Long::sum);
        }
        return byTemplate;
    }

    private long totalCount(Map<Key, Long> counts, SegmentType t) {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().type() == t)
                .mapToLong(Map.Entry::getValue).sum();
    }

    /** count 맵을 확률분포로 정규화(합=1). 비었으면 빈 맵. */
    private Map<Long, Double> normalize(Map<Long, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return Map.of();
        Map<Long, Double> dist = new HashMap<>(counts.size());
        for (Map.Entry<Long, Long> e : counts.entrySet()) {
            dist.put(e.getKey(), e.getValue() / (double) total);
        }
        return dist;
    }

    /**
     * Jensen-Shannon 발산(로그 밑 2 → [0,1], 대칭·유계). 0분모 없음.
     * 어느 한쪽이 비면(데이터 없음) 0(구별력 없음) 반환.
     */
    private double jensenShannon(Map<Long, Double> p, Map<Long, Double> q) {
        if (p.isEmpty() || q.isEmpty()) return 0.0;
        Set<Long> keys = new HashSet<>(p.keySet());
        keys.addAll(q.keySet());
        double klPm = 0.0;
        double klQm = 0.0;
        for (Long k : keys) {
            double pk = p.getOrDefault(k, 0.0);
            double qk = q.getOrDefault(k, 0.0);
            double m = (pk + qk) / 2.0;
            if (pk > 0) klPm += pk * log2(pk / m);
            if (qk > 0) klQm += qk * log2(qk / m);
        }
        double jsd = 0.5 * klPm + 0.5 * klQm;
        return Math.max(0.0, Math.min(1.0, jsd));   // 수치오차 방어
    }

    private double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private record Key(SegmentType type, String value, Long templateId) {}
}
