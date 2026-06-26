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
import com.ruleup.ruleup_backend.user.User;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 발견 추천(④) — rule-based 3단계 블렌딩.
 *  1) 세그먼트 점수(웜업): 유저 COUNTRY·GENDER·AGE_BAND의 TemplateSegmentScore 합.
 *  2) 관심사 보너스(콜드스타트): User.interestCategories ∋ template.category 면 가산(데이터 0이어도 추천).
 *  3) 진행 중 템플릿 제외.
 * 정렬: score desc, tie는 templateId asc. (LLM/임베딩은 후순위)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final double INTEREST_WEIGHT = 5.0;
    /** 생성화면 추천 태그는 최대 3건만 노출. limit 파라미터가 더 커도 여기서 자른다. */
    private static final int MAX_RECOMMENDATIONS = 3;

    private final UserRepository userRepo;
    private final SegmentScoreReader segmentScoreReader;
    private final SegmentResolver segmentResolver;
    private final RoutineCatalog catalog;
    private final ChallengeMemberRepository memberRepo;
    private final ChallengeRepository challengeRepo;

    public List<RecommendedRoutine> recommendRoutines(UUID userId, int limit) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return List.of();

        // 1) 세그먼트 점수 합 (배치가 채운 Redis 캐시에서 읽음 — DB 직격 없음)
        Map<Long, Double> segScore = new HashMap<>();
        for (Segment seg : segmentResolver.resolve(user)) {
            for (SegmentScoreEntry e : segmentScoreReader.scoresFor(seg.type(), seg.value())) {
                segScore.merge(e.templateId(), e.score(), Double::sum);
            }
        }
        // 2) 관심사(정규화: WAKE_UP→WAKEUP)
        Set<String> interests = user.getInterestCategories().stream()
                .map(c -> c.replace("_", ""))
                .collect(Collectors.toSet());
        // 3) 진행 중 템플릿 제외
        Set<Long> active = activeTemplateIds(userId);

        int cap = Math.min(Math.max(limit, 1), MAX_RECOMMENDATIONS);
        return catalog.candidates().stream()
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
