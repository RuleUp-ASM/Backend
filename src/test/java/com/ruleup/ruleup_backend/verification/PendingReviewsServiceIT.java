package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ObjectionSubmitRequest;
import com.ruleup.ruleup_backend.verification.dto.PendingReviewsResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.ObjectionService;
import com.ruleup.ruleup_backend.verification.service.PendingReviewsService;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 처리 대기함(pending-reviews) 통합 검증: 폴백 + 이의 제기 통합 목록, 관리자 권한.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PendingReviewsServiceIT {

    @Autowired PendingReviewsService pendingReviewsService;
    @Autowired VerificationManualService manualService;
    @Autowired ObjectionService objectionService;
    @Autowired VerificationDailyRepository dailyRepo;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    private Challenge groupChallenge(UUID ownerId) {
        Challenge c = Challenge.create(ownerId, "챌린지", null, null, "EXERCISE", ParticipationType.GROUP, null, 10,
                List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
                14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        c.activate();
        challengeRepository.saveAndFlush(c);
        memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), ownerId));
        return c;
    }

    private ChallengeMember join(Challenge c, UUID userId) {
        return memberRepository.saveAndFlush(ChallengeMember.join(c.getId(), userId, MemberStatus.ACTIVE));
    }

    @Test
    @DisplayName("폴백 + 이의 제기가 하나의 대기함으로 통합 조회된다")
    void unifiedInbox() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());

        // 폴백 제출자
        User f = newUser();
        join(c, f.getId());
        manualService.submit(f.getId(), c.getId(),
                new ManualVerificationRequest("SELF_CHECK", LocalDate.now().toString(), null, "폴백 증빙 글", true));

        // 이의 제기자(잠정 실패 일자)
        User o = newUser();
        ChallengeMember om = join(c, o.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        VerificationDaily d = VerificationDaily.open(om.getId(), c.getId(), o.getId(), date);
        d.recordProvisionalFailure("GPS_PRESENCE", "INSUFFICIENT_DWELL", Instant.now().plus(2, ChronoUnit.DAYS));
        dailyRepo.saveAndFlush(d);
        objectionService.submit(o.getId(), c.getId(),
                new ObjectionSubmitRequest("FAILURE", date.toString(), "이의 제기 글", null));

        PendingReviewsResponse res = pendingReviewsService.list(owner.getId(), c.getId());

        assertThat(res.pendingCount()).isEqualTo(2);
        assertThat(res.items()).extracting(PendingReviewsResponse.Item::kind)
                .containsExactlyInAnyOrder("FALLBACK", "OBJECTION");
        assertThat(res.items()).anySatisfy(it -> {
            if (it.kind().equals("OBJECTION")) {
                assertThat(it.content()).isEqualTo("이의 제기 글");
                assertThat(it.deadline()).isNotNull();
            }
        });
        assertThat(res.items()).anySatisfy(it -> {
            if (it.kind().equals("FALLBACK")) {
                assertThat(it.content()).isEqualTo("폴백 증빙 글");
                assertThat(it.deadline()).isNull();
            }
        });
    }

    @Test
    @DisplayName("방장/공동 관리자가 아니면 NOT_CHALLENGE_ADMIN")
    void nonAdminForbidden() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User u = newUser();
        join(c, u.getId());

        assertThatThrownBy(() -> pendingReviewsService.list(u.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_CHALLENGE_ADMIN);
    }
}
