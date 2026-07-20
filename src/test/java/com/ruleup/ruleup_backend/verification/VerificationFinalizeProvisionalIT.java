package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.routine.domain.SignalSource;
import com.ruleup.ruleup_backend.routine.domain.VerificationType;
import com.ruleup.ruleup_backend.routine.domain.WearableRequirement;
import com.ruleup.ruleup_backend.verification.domain.Objection;
import com.ruleup.ruleup_backend.verification.domain.ObjectionType;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.ObjectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
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
 * 실패 2단계(§8.7) 통합 검증: 그룹→잠정 실패(3일 창), 솔로→즉시 FAILED, 창 종료 후 lock(이의 제기 보류 포함).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VerificationFinalizeProvisionalIT {

    @Autowired VerificationFinalizeService finalizeService;
    @Autowired VerificationDailyRepository dailyRepo;
    @Autowired ObjectionRepository objectionRepo;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + u, null, "u-" + u, null, List.of()));
    }

    /** WAKE(도달형) 자동 챌린지 + ACTIVE 멤버. templateId=null → snapshot signalSource=WAKE 로 태그 해석. */
    private ChallengeMember activeMember(ParticipationType type, UUID userId) {
        var snap = new com.ruleup.ruleup_backend.routine.domain.VerificationConfig(
                SelectedMethod.AUTO, VerificationType.PHONE, SignalSource.GEOFENCE,
                WearableRequirement.NONE, List.of(), null);
        Integer cap = (type == ParticipationType.GROUP) ? 10 : null;
        Challenge c = Challenge.create(
                userId, "기상 챌린지", null, null, "WAKE_UP", type, null, cap, List.of("MON"),
                14, LocalDate.now(), null, snap, new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, true);
        c.activate();
        challengeRepository.saveAndFlush(c);
        ChallengeMember m = memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), userId));
        return m;
    }

    private VerificationDaily duePending(ChallengeMember m, LocalDate date) {
        VerificationDaily d = VerificationDaily.open(m.getId(), m.getChallengeId(), m.getUserId(), date);
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        d.applyWindow(past, past);   // finalizeAfter 과거 → 배치 대상
        return dailyRepo.saveAndFlush(d);
    }

    @Test
    @DisplayName("그룹 도달형 미충족 → 잠정 실패(FAILED_PROVISIONAL) + 3일 이의 제기 창")
    void groupFailBecomesProvisional() {
        ChallengeMember m = activeMember(ParticipationType.GROUP, newUser().getId());
        VerificationDaily d = duePending(m, LocalDate.now().minusDays(1));

        finalizeService.finalizeDue();

        VerificationDaily reloaded = dailyRepo.findById(d.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VerificationStatus.FAILED_PROVISIONAL);
        assertThat(reloaded.getDisputeClosesAt()).isNotNull();
        assertThat(reloaded.getVerifiedAt()).isNull();   // 확정 아님 → 온도 미반영
    }

    @Test
    @DisplayName("솔로 도달형 미충족 → 즉시 FAILED 확정")
    void soloFailIsImmediate() {
        ChallengeMember m = activeMember(ParticipationType.SOLO, newUser().getId());
        VerificationDaily d = duePending(m, LocalDate.now().minusDays(1));

        finalizeService.finalizeDue();

        VerificationDaily reloaded = dailyRepo.findById(d.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(reloaded.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("이의 제기 창 종료 + 미제기 → 잠정 실패가 FAILED 로 lock")
    void provisionalLocksAfterWindow() {
        ChallengeMember m = activeMember(ParticipationType.GROUP, newUser().getId());
        VerificationDaily d = VerificationDaily.open(m.getId(), m.getChallengeId(), m.getUserId(), LocalDate.now().minusDays(4));
        d.recordProvisionalFailure("GPS_PRESENCE", "INSUFFICIENT_DWELL", Instant.now().minus(1, ChronoUnit.HOURS));   // 창 이미 종료
        dailyRepo.saveAndFlush(d);

        finalizeService.lockExpiredProvisionalFailures();

        assertThat(dailyRepo.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(VerificationStatus.FAILED);
    }

    @Test
    @DisplayName("창 종료됐어도 미처리 이의 제기가 있으면 lock 보류")
    void provisionalHeldWhenObjectionPending() {
        ChallengeMember m = activeMember(ParticipationType.GROUP, newUser().getId());
        LocalDate date = LocalDate.now().minusDays(4);
        VerificationDaily d = VerificationDaily.open(m.getId(), m.getChallengeId(), m.getUserId(), date);
        Instant deadline = Instant.now().minus(1, ChronoUnit.HOURS);
        d.recordProvisionalFailure("GPS_PRESENCE", "INSUFFICIENT_DWELL", deadline);
        dailyRepo.saveAndFlush(d);
        objectionRepo.saveAndFlush(Objection.submit(m.getChallengeId(), m.getId(), m.getUserId(),
                date, ObjectionType.FAILURE, "정전으로 신호 미발생", null, deadline));

        finalizeService.lockExpiredProvisionalFailures();

        assertThat(dailyRepo.findById(d.getId()).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.FAILED_PROVISIONAL);   // 보류
    }
}
