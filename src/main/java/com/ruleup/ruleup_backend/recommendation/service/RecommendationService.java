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
        // 2) 관심사 보너스 — 루틴 카테고리가 관심 카테고리와 같은 12종 코드로 통일됨(V4)
        Set<String> interests = Set.copyOf(user.getInterestCategories());
        // 3) 진행 중 템플릿 제외
        Set<Long> active = activeTemplateIds(userId);

        int cap = Math.min(Math.max(limit, 1), MAX_RECOMMENDATIONS);
        // 동점은 사용자별 결정적 키로 깬다(templateId 오름차순이면 전원에게 늘 같은 3건이 나간다 — RecommendationShuffle).
        List<Scored> ranked = catalog.candidates().stream()
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
                        .thenComparingLong(s -> RecommendationShuffle.orderKey(userId, s.candidate.id())))
                .toList();

        List<RecommendedRoutine> result = pickDiverse(ranked, cap).stream()
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

    /**
     * 점수 순서를 유지하되 <b>카테고리가 겹치지 않게</b> 먼저 뽑고, 모자라면 겹침을 허용해 채운다.
     *
     * <p>다양성 제약이 없으면 3건이 거의 항상 한 카테고리로 몰린다. 관심사 보너스가 평평해서
     * 한 카테고리 전체가 동점이 되고, 그 카테고리가 세 자리를 다 먹기 때문이다(관심사를 여러 개 골라도
     * 정렬에서 앞서는 하나가 독식한다). 생성 화면의 추천은 "무엇을 시작할지 고르는" 화면이라
     * 같은 결의 루틴 3개보다 다른 결 3개가 고를 거리가 된다.
     *
     * <p>3건 보장이 다양성보다 우선이라 두 번째 패스에서 겹침을 허용한다.
     */
    private List<Scored> pickDiverse(List<Scored> ranked, int cap) {
        List<Scored> picked = new ArrayList<>(cap);
        Set<String> usedCategories = new HashSet<>();
        Set<Long> pickedIds = new HashSet<>();

        for (Scored s : ranked) {
            if (picked.size() >= cap) break;
            if (usedCategories.add(s.candidate.category())) {
                picked.add(s);
                pickedIds.add(s.candidate.id());
            }
        }
        for (Scored s : ranked) {           // 카테고리 수가 모자랄 때만 도는 폴백
            if (picked.size() >= cap) break;
            if (pickedIds.add(s.candidate.id())) picked.add(s);
        }
        return picked;
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
