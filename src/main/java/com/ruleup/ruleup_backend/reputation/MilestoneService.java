package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.reputation.domain.Milestone;
import com.ruleup.ruleup_backend.reputation.domain.MilestoneType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 마일스톤 멱등 적재(마이프로필 §8). 각 훅이 자기 이벤트를 (userId,type,dedupKey) 유니크로 append.
 *  - SIGNUP           : 가입 시(AuthService).
 *  - TIER_REACHED     : 온도 배치에서 앵커(50/60/70/75/80/85/90) 상향 통과 시.
 *  - FIRST_COMPLETION : 첫 완주 확인 시.
 *  - STREAK           : 연속 성공 임계(10/30/50/100) 도달 시.
 */
@Service
@RequiredArgsConstructor
public class MilestoneService {

    private static final BigDecimal COMPLETION_RATE = new BigDecimal("90");
    private static final int[] TIERS = {50, 60, 70, 75, 80, 85, 90};
    private static final int[] STREAKS = {10, 30, 50, 100};

    private final MilestoneRepository milestoneRepo;
    private final RoutineOutcomeRepository outcomeRepo;
    private final ChallengeMemberRepository memberRepository;

    /** 가입 마일스톤(멱등). */
    public void recordSignup(UUID userId, LocalDate date) {
        insertIfAbsent(userId, MilestoneType.SIGNUP, "SIGNUP", "RuleUp 시작", date);
    }

    /** 온도 배치가 유저 1명 처리 후 호출: 티어 통과·첫 완주·스트릭 마일스톤을 멱등 적재. */
    public void detectDaily(UUID userId, BigDecimal prevTemp, BigDecimal newTemp, LocalDate today) {
        // TIER_REACHED: prevTemp < tier ≤ newTemp (상향 통과)
        double prev = (prevTemp != null) ? prevTemp.doubleValue() : 0;
        double now = newTemp.doubleValue();
        for (int tier : TIERS) {
            if (prev < tier && now >= tier)
                insertIfAbsent(userId, MilestoneType.TIER_REACHED, String.valueOf(tier),
                        "첫 " + tier + "°C 달성", today);
        }

        // FIRST_COMPLETION: 완주 멤버십이 하나라도 있으면 최초 1회
        if (!milestoneRepo.existsByUserIdAndType(userId, MilestoneType.FIRST_COMPLETION) && hasCompleted(userId)) {
            insertIfAbsent(userId, MilestoneType.FIRST_COMPLETION, "FIRST", "첫 챌린지 완주", today);
        }

        // STREAK: 최대 연속 성공일 임계 도달
        int max = maxStreak(userId);
        for (int s : STREAKS) {
            if (max >= s)
                insertIfAbsent(userId, MilestoneType.STREAK, String.valueOf(s), s + "일 연속 성공", today);
        }
    }

    private boolean hasCompleted(UUID userId) {
        return memberRepository.findByUserId(userId).stream()
                .anyMatch(m -> m.getProgressRate() != null && m.getProgressRate().compareTo(COMPLETION_RATE) >= 0);
    }

    /** 유저 전체 아웃컴에서 챌린지별 최대 연속 성공일. */
    private int maxStreak(UUID userId) {
        Map<UUID, TreeMap<LocalDate, Boolean>> byChallenge = new HashMap<>();
        for (RoutineOutcome o : outcomeRepo.findByUserId(userId)) {
            if (o.getStatus() != VerificationStatus.SUCCESS && o.getStatus() != VerificationStatus.FAILED) continue;
            byChallenge.computeIfAbsent(o.getChallengeId(), k -> new TreeMap<>())
                    .put(o.getTargetDate(), o.getStatus() == VerificationStatus.SUCCESS);
        }
        int max = 0;
        for (TreeMap<LocalDate, Boolean> days : byChallenge.values()) {
            int run = 0;
            LocalDate prev = null;
            for (Map.Entry<LocalDate, Boolean> e : days.entrySet()) {
                boolean consecutive = prev != null && e.getKey().equals(prev.plusDays(1));
                if (e.getValue()) run = (consecutive && run > 0) ? run + 1 : 1;
                else run = 0;
                max = Math.max(max, run);
                prev = e.getKey();
            }
        }
        return max;
    }

    private void insertIfAbsent(UUID userId, MilestoneType type, String key, String label, LocalDate date) {
        if (milestoneRepo.existsByUserIdAndTypeAndDedupKey(userId, type, key)) return;
        try {
            milestoneRepo.save(Milestone.of(userId, type, key, label, date));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 동시성으로 이미 적재됨 → 멱등 무시.
        }
    }
}
