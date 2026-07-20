package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.ObjectionSubmitRequest;
import com.ruleup.ruleup_backend.verification.dto.VerificationDetailResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.ObjectionService;
import com.ruleup.ruleup_backend.verification.service.VerificationReadService;
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

/**
 * 조회 v3: 상세의 today.objection(available/deadline/objectionId) + progress todayStatus FAILED_PROVISIONAL.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VerificationReadV3IT {

    @Autowired VerificationReadService readService;
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

    @Test
    @DisplayName("상세: 오늘 잠정 실패면 today.status=FAILED_PROVISIONAL + objection(available=true)")
    void detailShowsProvisionalObjection() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User u = newUser();
        ChallengeMember m = memberRepository.saveAndFlush(ChallengeMember.join(c.getId(), u.getId(), MemberStatus.ACTIVE));
        LocalDate today = LocalDate.now();
        VerificationDaily d = VerificationDaily.open(m.getId(), c.getId(), u.getId(), today);
        d.recordProvisionalFailure("GPS_PRESENCE", "INSUFFICIENT_DWELL", Instant.now().plus(2, ChronoUnit.DAYS));
        dailyRepo.saveAndFlush(d);

        VerificationDetailResponse res = readService.detail(u.getId(), c.getId(), 7);
        var todayDto = res.verification().today();
        assertThat(todayDto.status()).isEqualTo("FAILED_PROVISIONAL");
        assertThat(todayDto.objection()).isNotNull();
        assertThat(todayDto.objection().available()).isTrue();
        assertThat(todayDto.objection().deadline()).isNotNull();
        assertThat(todayDto.objection().objectionId()).isNull();

        // 이의 제기 후 → available=false, objectionId 채워짐
        var submitted = objectionService.submit(u.getId(), c.getId(),
                new ObjectionSubmitRequest("FAILURE", today.toString(), "정전으로 신호 미발생", null));
        var after = readService.detail(u.getId(), c.getId(), 7).verification().today();
        assertThat(after.objection().available()).isFalse();
        assertThat(after.objection().objectionId()).isEqualTo(submitted.objectionId());
    }

    @Test
    @DisplayName("progress: 캐시 todayStatus 가 FAILED_PROVISIONAL 로 노출된다")
    void progressExposesProvisional() {
        User owner = newUser();
        Challenge c = groupChallenge(owner.getId());
        User u = newUser();
        ChallengeMember m = ChallengeMember.join(c.getId(), u.getId(), MemberStatus.ACTIVE);
        m.applyProgress(0, 0, BigDecimal.ZERO, VerificationStatus.FAILED_PROVISIONAL, Instant.now());
        memberRepository.saveAndFlush(m);

        List<ChallengeProgress> list = readService.progress(u.getId(), "ACTIVE");
        assertThat(list).anySatisfy(p ->
                assertThat(p.todayStatus()).isEqualTo("FAILED_PROVISIONAL"));
    }
}
