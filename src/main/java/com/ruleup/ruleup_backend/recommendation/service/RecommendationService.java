package com.ruleup.ruleup_backend.recommendation.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.recommendation.dto.RecommendedRoutine;
import com.ruleup.ruleup_backend.recommendation.dto.SegmentScoreEntry;
import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;
import com.ruleup.ruleup_backend.routine.service.RoutineCatalog;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 발견 추천(④) — rule-based 3단계 블렌딩.
 *  1) 세그먼트 점수(웜업): 유저 세그먼트별 TemplateSegmentScore 를 특성 가중치 w(type)로 곱해 합(§6·§8.1).
 *  2) 관심사 보너스(콜드스타트): User.interestCategories ∋ template.category 면 가산(데이터 0이어도 추천).
 *  3) 진행 중 템플릿 제외.
 * 정렬: score desc, tie는 templateId asc. (LLM/임베딩은 후순위)
 *
 * <p>가중치의 값은 배치가 학습해 저장하고(§8.1), 조회는 저장 가중치를 곱하기만 한다(학습은 배치, 곱셈은 조회).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private static final double INTEREST_WEIGHT = 5.0;
    /** 생성화면 추천 태그는 최대 3건만 노출. limit 파라미터가 더 커도 여기서 자른다. */
    private static final int MAX_RECOMMENDATIONS = 3;

    /**
     * 관심 카테고리(12종, 2026-08-03 확정) → 루틴 템플릿 카테고리(15종, 카탈로그 스키마) 매핑.
     * 두 코드 체계는 별개라 명시적 매핑으로 연결한다. ETC 는 대응 카테고리 없음(탐색 폴백).
     */
    private static final Map<String, Set<String>> INTEREST_TO_ROUTINE = Map.ofEntries(
            Map.entry("EXERCISE", Set.of("EXERCISE")),
            Map.entry("WAKE_SLEEP", Set.of("WAKEUP")),
            Map.entry("DIET_HEALTH", Set.of("HEALTH", "COOKING")),
            Map.entry("STUDY", Set.of("STUDY", "CODING")),
            Map.entry("READING", Set.of("READING")),
            Map.entry("MIND", Set.of("MEDITATION", "WRITING")),
            Map.entry("FINANCE", Set.of("FINANCE")),
            Map.entry("HOBBY", Set.of("HOBBY", "MUSIC")),
            Map.entry("HOUSEKEEPING", Set.of("ENVIRONMENT", "COOKING")),
            Map.entry("CAREER_PRODUCTIVITY", Set.of("WORK", "CODING")),
            Map.entry("DETOX", Set.of("MEDITATION")),
            Map.entry("ETC", Set.of()));

    private final UserRepository userRepo;
    private final SegmentScoreReader segmentScoreReader;
    private final SegmentTypeWeightReader segmentTypeWeightReader;
    private final SegmentResolver segmentResolver;
    private final RoutineCatalog catalog;
    private final ChallengeMemberRepository memberRepo;
    private final ChallengeRepository challengeRepo;

    public List<RecommendedRoutine> recommendRoutines(UUID userId, int limit) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return List.of();

        // 1) 세그먼트 점수 합 (배치가 채운 캐시(Caffeine)에서 읽음 — DB 직격 없음)
        //    특성 가중치 w(type)를 곱해 합산: 전체 인기(GLOBAL)가 개인 특성을 덮지 않게 축별로 가중(§6·§8.1).
        Map<Long, Double> segScore = new HashMap<>();
        for (Segment seg : segmentResolver.resolve(user)) {
            double w = segmentTypeWeightReader.of(seg.type());
            for (SegmentScoreEntry e : segmentScoreReader.scoresFor(seg.type(), seg.value())) {
                segScore.merge(e.templateId(), w * e.score(), Double::sum);
            }
        }
        // 2) 관심사 12종 → 루틴 카테고리 15종 매핑(별개 코드 체계 — InterestCategory 주석 참조)
        Set<String> interests = user.getInterestCategories().stream()
                .flatMap(c -> INTEREST_TO_ROUTINE.getOrDefault(c, Set.of()).stream())
                .collect(Collectors.toSet());
        // 3) 진행 중 템플릿 제외
        Set<Long> active = activeTemplateIds(userId);

        int cap = Math.min(Math.max(limit, 1), MAX_RECOMMENDATIONS);
        List<RecommendedRoutine> result = catalog.candidates().stream()
                .filter(c -> !active.contains(c.id()))
                .map(c -> {
                    double seg = segScore.getOrDefault(c.id(), 0.0);
                    boolean interestMatch = interests.contains(c.category());
                    double score = seg + (interestMatch ? INTEREST_WEIGHT : 0.0);
                    String reason = (seg > 0) ? "POPULAR_IN_SEGMENT"
                            : (interestMatch ? "INTEREST_MATCH" : "EXPLORE");
                    return new Scored(c, score, reason);
                })
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed()
                        .thenComparingLong(s -> s.candidate.id()))
                .limit(cap)
                .map(s -> new RecommendedRoutine(
                        s.candidate.id(),
                        s.candidate.name(),                 // 자동입력 제목 = 템플릿 이름
                        descriptionOf(s.candidate.id()),    // 자동입력 설명 = 템플릿 설명
                        s.candidate.category(),
                        s.reason))
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("발견 추천 userId={} → {}건 [{}] (관심사 {}개, 진행중 제외 {}개)",
                    userId, result.size(),
                    result.stream().map(r -> r.templateId() + ":" + r.reason()).toList(),
                    interests.size(), active.size());
        }
        return result;
    }

    private Set<Long> activeTemplateIds(UUID userId) {
        List<ChallengeMember> members = memberRepo.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);
        if (members.isEmpty()) return Set.of();
        List<UUID> challengeIds = members.stream().map(ChallengeMember::getChallengeId).toList();
        return challengeRepo.findAllById(challengeIds).stream()
                .map(Challenge::getTemplateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String descriptionOf(long templateId) {
        return catalog.findById(templateId).map(RoutineTemplate::getDescription).orElse(null);
    }

    private record Scored(RoutineCandidate candidate, double score, String reason) {}
}
