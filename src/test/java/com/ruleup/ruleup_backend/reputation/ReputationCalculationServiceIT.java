package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매너 온도 일일 배치 통합 검증(테크스펙 §4) — 실제 MySQL(Testcontainers).
 *  - 완벽 방 1개 → 온도 상승 + 상태(V·자격일) 갱신, 멱등(같은 날 재계산 skip).
 *  - 죽은 방 → 온도 하락. 활성 방 없음 → 36.5 유지.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReputationCalculationServiceIT {

    @Autowired ReputationCalculationService service;
    @Autowired ReputationScoreRepository scoreRepository;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("완벽 방 1개면 온도가 36.5보다 오르고 V·자격일이 갱신되며, 같은 날 재계산은 멱등")
    void perfectRoomRaisesTemperature() {
        UUID userId = seedUserWithScore();
        seedActiveMember(userId, 10, 0);   // 성공10 실패0 → r=1 → c=+1 → s_d=1.0
        LocalDate today = LocalDate.now();

        assertThat(service.recalculate(userId, today)).isTrue();

        ReputationScore s = scoreRepository.findById(userId).orElseThrow();
        assertThat(s.getMannerTemperature()).isGreaterThan(new BigDecimal("36.50"));
        assertThat(s.getVolumeIndex()).isGreaterThan(BigDecimal.ZERO);
        assertThat(s.getQualifyingDays()).isEqualTo(1);
        assertThat(s.getLastCalculatedDate()).isEqualTo(today);

        // 같은 날 재계산 → 멱등(변화 없음)
        BigDecimal temp = s.getMannerTemperature();
        assertThat(service.recalculate(userId, today)).isFalse();
        assertThat(scoreRepository.findById(userId).orElseThrow().getMannerTemperature()).isEqualByComparingTo(temp);
    }

    @Test
    @DisplayName("죽은 방(실패만)이면 온도가 36.5 아래로 내려간다")
    void deadRoomLowersTemperature() {
        UUID userId = seedUserWithScore();
        seedActiveMember(userId, 0, 10);   // r=0 → c=−1 → s_d=−1.0
        LocalDate today = LocalDate.now();

        assertThat(service.recalculate(userId, today)).isTrue();

        ReputationScore s = scoreRepository.findById(userId).orElseThrow();
        assertThat(s.getMannerTemperature()).isLessThan(new BigDecimal("36.50"));
    }

    @Test
    @DisplayName("활성 방이 없으면 온도는 36.5로 유지된다")
    void noActiveRoomsKeepsInitial() {
        UUID userId = seedUserWithScore();
        LocalDate today = LocalDate.now();

        assertThat(service.recalculate(userId, today)).isTrue();

        ReputationScore s = scoreRepository.findById(userId).orElseThrow();
        assertThat(s.getMannerTemperature()).isEqualByComparingTo("36.50");
        assertThat(s.getQualifyingDays()).isZero();
    }

    // ===== 헬퍼 =====

    private UUID seedUserWithScore() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + uniq, null, "온도러" + uniq, null, List.of()));
        scoreRepository.saveAndFlush(ReputationScore.createDefault(user));
        return user.getId();
    }

    private void seedActiveMember(UUID userId, int successDays, int failDays) {
        Challenge c = Challenge.create(
                userId, "온도 챌린지", null, null,
                "EXERCISE", ParticipationType.SOLO, null, null, List.of("MON"),
                14, LocalDate.now(),
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        c.activate();   // RECRUITING → ACTIVE
        challengeRepository.saveAndFlush(c);

        ChallengeMember m = ChallengeMember.join(c.getId(), userId, MemberStatus.ACTIVE);
        m.applyProgress(successDays, failDays, BigDecimal.ZERO, null, null);
        memberRepository.saveAndFlush(m);
    }
}
