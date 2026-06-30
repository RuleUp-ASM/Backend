package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeActivationService;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시작일 도달 → ACTIVE 전환 배치(§5.7) 통합 검증 — 실제 MySQL(Testcontainers).
 *  - native FOR UPDATE SKIP LOCKED 폴링 쿼리가 올바른 행만 잡는지(컬럼/문법/DATE 비교) 실 DB로 확인.
 *  - 시작일 도달 + APPROVED → ACTIVE 전환.
 *  - 시작일 미도달 / 미승인(PENDING_REVIEW) 은 RECRUITING 유지.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeActivationServiceIT {

    @Autowired ChallengeActivationService activationService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired UserRepository userRepository;

    private User newOwner() {
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + UUID.randomUUID(), null, "방장", null, List.of()));
    }

    private Challenge challengeStartingOn(UUID ownerId, LocalDate startDate) {
        return Challenge.create(
                ownerId, "테스트 챌린지", null, null,
                "EXERCISE", ParticipationType.SOLO, null, List.of("MON"),
                14, startDate,
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
    }

    @Test
    @DisplayName("시작일 도달 + APPROVED 챌린지는 ACTIVE 로 전환된다")
    void activatesApprovedChallengeWhenStartDateReached() {
        User owner = newOwner();
        Challenge c = challengeStartingOn(owner.getId(), LocalDate.now());   // 오늘 시작
        c.approveModeration(Instant.now());
        challengeRepository.saveAndFlush(c);

        activationService.activateDueChallenges();

        Challenge reloaded = challengeRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.ACTIVE);
    }

    @Test
    @DisplayName("시작일이 아직 안 온 챌린지는 RECRUITING 유지")
    void keepsRecruitingWhenStartDateNotReached() {
        User owner = newOwner();
        Challenge c = challengeStartingOn(owner.getId(), LocalDate.now().plusDays(3));   // 3일 뒤 시작
        c.approveModeration(Instant.now());
        challengeRepository.saveAndFlush(c);

        activationService.activateDueChallenges();

        Challenge reloaded = challengeRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.RECRUITING);
    }

    @Test
    @DisplayName("시작일이 도달해도 미승인(PENDING_REVIEW)이면 활성화하지 않는다")
    void ignoresUnapprovedChallenge() {
        User owner = newOwner();
        Challenge c = challengeStartingOn(owner.getId(), LocalDate.now());   // 오늘 시작이지만 검수 전
        challengeRepository.saveAndFlush(c);

        activationService.activateDueChallenges();

        Challenge reloaded = challengeRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChallengeStatus.RECRUITING);
    }
}
