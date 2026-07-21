package com.ruleup.ruleup_backend.me;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.me.dto.MeHomeResponse;
import com.ruleup.ruleup_backend.me.service.MeHomeService;
import com.ruleup.ruleup_backend.reputation.ReputationScoreRepository;
import com.ruleup.ruleup_backend.reputation.domain.ReputationScore;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MeHomeServiceIT {

    @Autowired MeHomeService homeService;
    @Autowired UserRepository userRepository;
    @Autowired ReputationScoreRepository reputationScoreRepository;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private void membership(User u, ParticipationType type, ChallengeStatus status, String progressRate) {
        Integer cap = (type == ParticipationType.GROUP) ? 10 : null;
        Challenge c = Challenge.create(u.getId(), "챌린지", null, null, "EXERCISE", type, null, cap, List.of("MON"),
                14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        if (status != ChallengeStatus.UPCOMING) c.activate();
        if (status == ChallengeStatus.COMPLETED) c.complete();
        challengeRepository.saveAndFlush(c);
        ChallengeMember m = ChallengeMember.owner(c.getId(), u.getId());
        m.applyCounts(0, 0, new BigDecimal(progressRate));   // progressRate 세팅
        memberRepository.saveAndFlush(m);
    }

    @Test
    @DisplayName("홈: 닉네임/온도 + counts(완주·진행·그룹) 조립")
    void home() {
        User u = newUser();
        reputationScoreRepository.saveAndFlush(ReputationScore.createDefault(u));
        membership(u, ParticipationType.SOLO, ChallengeStatus.ACTIVE, "100");   // 완주
        membership(u, ParticipationType.GROUP, ChallengeStatus.ACTIVE, "50");   // 진행 + 그룹
        membership(u, ParticipationType.SOLO, ChallengeStatus.ACTIVE, "30");    // 진행

        MeHomeResponse res = homeService.home(u.getId());

        assertThat(res.nickname()).isEqualTo(u.getNickname());
        assertThat(res.nicknameStatus()).isEqualTo("PENDING");
        assertThat(res.mannerTemperature()).isEqualByComparingTo("36.5");
        assertThat(res.counts().completed()).isEqualTo(1);
        assertThat(res.counts().inProgress()).isEqualTo(2);
        assertThat(res.counts().groups()).isEqualTo(1);
    }

    @Test
    @DisplayName("멤버십 없으면 counts 0, 온도는 초기값")
    void emptyHome() {
        User u = newUser();
        MeHomeResponse res = homeService.home(u.getId());
        assertThat(res.mannerTemperature()).isEqualByComparingTo("36.5");
        assertThat(res.counts().completed()).isZero();
        assertThat(res.counts().inProgress()).isZero();
        assertThat(res.counts().groups()).isZero();
    }
}
