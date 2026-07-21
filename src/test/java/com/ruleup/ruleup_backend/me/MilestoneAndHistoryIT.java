package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.me.dto.MeReputationHistoryResponse;
import com.ruleup.ruleup_backend.me.service.MeReputationHistoryService;
import com.ruleup.ruleup_backend.recommendation.domain.RoutineOutcome;
import com.ruleup.ruleup_backend.recommendation.repository.RoutineOutcomeRepository;
import com.ruleup.ruleup_backend.reputation.MilestoneService;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MilestoneAndHistoryIT {

    @Autowired MilestoneService milestoneService;
    @Autowired MeReputationHistoryService historyService;
    @Autowired ReputationScoreRepository scoreRepository;
    @Autowired RoutineOutcomeRepository outcomeRepo;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    @Test
    @DisplayName("가입/티어/첫완주/스트릭 마일스톤 적재 + 히스토리 조회")
    void milestonesAndHistory() {
        User u = newUser();
        LocalDate today = LocalDate.now();

        // peak 기록된 점수
        ReputationScore score = ReputationScore.createDefault(u);
        score.applyCalculation(0, 0, 0, null, new BigDecimal("52.00"), today);
        score.updatePeakIfHigher(new BigDecimal("52.00"), today);
        scoreRepository.saveAndFlush(score);

        // 완주 멤버십(첫 완주)
        Challenge c = Challenge.create(u.getId(), "챌린지", null, null, "EXERCISE", ParticipationType.SOLO, null, null,
                List.of("MON"), 14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE), Anonymity.REAL, false);
        challengeRepository.saveAndFlush(c);
        ChallengeMember m = ChallengeMember.owner(c.getId(), u.getId());
        m.applyCounts(0, 0, new BigDecimal("100"));
        memberRepository.saveAndFlush(m);

        // 10일 연속 성공(스트릭)
        for (int i = 0; i < 10; i++) {
            outcomeRepo.saveAndFlush(RoutineOutcome.record(u.getId(), c.getId(), m.getId(), null, "EXERCISE",
                    today.minusDays(20 - i), VerificationStatus.SUCCESS, VerifiedVia.AUTO, null, Instant.now()));
        }

        milestoneService.recordSignup(u.getId(), today.minusDays(30));
        milestoneService.detectDaily(u.getId(), new BigDecimal("40.00"), new BigDecimal("52.00"), today);   // 50 통과

        MeReputationHistoryResponse res = historyService.history(u.getId());

        assertThat(res.peak().temperature()).isEqualByComparingTo("52.00");
        assertThat(res.milestones()).extracting(MeReputationHistoryResponse.Milestone::type)
                .contains("SIGNUP", "TIER_REACHED", "FIRST_COMPLETION", "STREAK");
        assertThat(res.milestones()).anySatisfy(mi -> {
            if (mi.type().equals("TIER_REACHED")) assertThat(mi.label()).isEqualTo("첫 50°C 달성");
        });
        assertThat(res.milestones()).anySatisfy(mi -> {
            if (mi.type().equals("STREAK")) assertThat(mi.label()).isEqualTo("10일 연속 성공");
        });
    }

    @Test
    @DisplayName("마일스톤 적재는 멱등(중복 호출해도 1건)")
    void idempotent() {
        User u = newUser();
        milestoneService.recordSignup(u.getId(), LocalDate.now());
        milestoneService.recordSignup(u.getId(), LocalDate.now());
        long signups = historyService.history(u.getId()).milestones().stream()
                .filter(m -> m.type().equals("SIGNUP")).count();
        assertThat(signups).isEqualTo(1);
    }
}
