package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final SegmentResolver segmentResolver;
    private final TemplateSegmentScoreRepository scoreRepository;
    private final SegmentScoreReader segmentScoreReader;

    /** 집계 윈도우(일). 이 기간 안에 생성된 챌린지만 인기도에 반영. */
    @Value("${app.recommendation.segment-window-days:30}")
    private int windowDays;

    /** 매일 04:00 KST: 최근 windowDays일 챌린지로 세그먼트 점수 전면 재집계. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void rebuild() {
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));

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

        // 커밋된 뒤에만 캐시 무효화 → 다음 조회가 최신 점수로 다시 채워진다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                segmentScoreReader.evictAll();
            }
        });
    }

    private record Key(SegmentType type, String value, Long templateId) {}
}
