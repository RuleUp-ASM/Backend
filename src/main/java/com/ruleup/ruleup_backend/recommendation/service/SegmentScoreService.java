package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.recommendation.domain.SegmentType;
import com.ruleup.ruleup_backend.recommendation.domain.TemplateSegmentScore;
import com.ruleup.ruleup_backend.recommendation.repository.TemplateSegmentScoreRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 그래서 점수는 실시간이 아니라 주기적으로(기본: 매일 04:00 KST) "그때까지의 챌린지 전체"를
 * 생성자 세그먼트별로 다시 집계해 TemplateSegmentScore 를 통째로 재작성한다(idempotent).
 *
 * <p>집계 단위: (segmentType, segmentValue, templateId) → 선택 수.
 * 세그먼트 축은 {@link SegmentResolver} 가 내보내는 그대로(GLOBAL·COUNTRY·GENDER·AGE_BAND·PLATFORM).
 * GLOBAL 은 모든 유저가 공유하므로, 인구통계가 전혀 없는 유저도 '전체 인기도'로 추천받는다.
 *
 * <p>현재는 전량 재집계(단순·정확). 데이터가 커지면 증분(마지막 실행 이후 챌린지만)으로 최적화 가능.
 */
@Service
@RequiredArgsConstructor
public class SegmentScoreService {

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final SegmentResolver segmentResolver;
    private final TemplateSegmentScoreRepository scoreRepository;

    /** 매일 04:00 KST: 누적 챌린지로 세그먼트 점수 전면 재집계. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void rebuild() {
        Map<Key, Long> counts = new HashMap<>();
        Map<UUID, Optional<User>> userCache = new HashMap<>();   // 생성자 1명이 여러 챌린지 → 조회 캐시

        for (Challenge c : challengeRepository.findAll()) {
            if (c.getDeletedAt() != null || c.getTemplateId() == null) continue;   // 직접 입력/삭제분 제외
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
    }

    private record Key(SegmentType type, String value, Long templateId) {}
}
