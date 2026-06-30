package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.Anonymity;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationCloseService;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
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
 * REJECTED 1시간 수정창 마감 배치(§5.1 상태머신 끝단 / §8.2) 통합 검증 — 실제 MySQL(Testcontainers).
 *  - native FOR UPDATE SKIP LOCKED 폴링 쿼리가 올바른 행만 잡는지(컬럼/문법) 실 DB로 확인.
 *  - 수정창 경과 → 영구 닫힘(소프트 삭제) + 생성자 알림.
 *  - 수정창 이내 → 그대로 유지.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeModerationCloseServiceIT {

    @Autowired ChallengeModerationCloseService closeService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;

    private User newOwner() {
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + UUID.randomUUID(), null, "방장", null, List.of()));
    }

    private Challenge sampleChallenge(UUID ownerId) {
        return Challenge.create(
                ownerId, "테스트 챌린지", null, null,
                "EXERCISE", ParticipationType.SOLO, null, List.of("MON"),
                14, LocalDate.now(),
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
    }

    @Test
    @DisplayName("수정창 경과한 REJECTED는 영구 닫힘(소프트 삭제) + 생성자 알림")
    void closesRejectedWhenFixWindowExpired() {
        User owner = newOwner();
        Challenge c = sampleChallenge(owner.getId());
        // 2시간 전 거절, 수정창은 1시간 전에 이미 만료.
        c.rejectModeration(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        challengeRepository.saveAndFlush(c);

        closeService.closeExpiredRejected();

        assertThat(challengeRepository.findByIdAndDeletedAtIsNull(c.getId())).isEmpty();
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                .anyMatch(n -> n.getType() == NotificationType.CHALLENGE_CLOSED);
    }

    @Test
    @DisplayName("수정창 이내(미만료) REJECTED는 닫지 않는다")
    void keepsRejectedWithinFixWindow() {
        User owner = newOwner();
        Challenge c = sampleChallenge(owner.getId());
        // 방금 거절, 수정창 1시간 남음.
        c.rejectModeration(Instant.now(), Instant.now().plusSeconds(3600));
        challengeRepository.saveAndFlush(c);

        closeService.closeExpiredRejected();

        assertThat(challengeRepository.findByIdAndDeletedAtIsNull(c.getId())).isPresent();
    }

    @Test
    @DisplayName("APPROVED는 수정창과 무관하게 닫지 않는다")
    void ignoresApproved() {
        User owner = newOwner();
        Challenge c = sampleChallenge(owner.getId());
        c.approveModeration(Instant.now());
        challengeRepository.saveAndFlush(c);

        closeService.closeExpiredRejected();

        assertThat(challengeRepository.findByIdAndDeletedAtIsNull(c.getId())).isPresent();
    }
}
