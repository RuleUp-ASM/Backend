package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.dto.ObjectionDecisionRequest;
import com.ruleup.ruleup_backend.verification.dto.ObjectionResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionSubmitRequest;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.ObjectionService;
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
 * 이의 제기(§8.7) 통합 검증: 제출 유효성 + 승인/기각 결과.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ObjectionServiceIT {

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

    private Challenge newChallenge(UUID ownerId, ParticipationType type) {
        Integer cap = (type == ParticipationType.GROUP) ? 10 : null;
        Challenge c = Challenge.create(ownerId, "챌린지", null, null, "EXERCISE", type, null, cap, List.of("MON"),
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

    /** 멤버의 date 일자를 잠정 실패로 만든다(창 열림). */
    private VerificationDaily provisional(ChallengeMember m, LocalDate date, Instant deadline) {
        VerificationDaily d = VerificationDaily.open(m.getId(), m.getChallengeId(), m.getUserId(), date);
        d.recordProvisionalFailure("GPS_PRESENCE", "INSUFFICIENT_DWELL", deadline);
        return dailyRepo.saveAndFlush(d);
    }

    private ObjectionSubmitRequest req(LocalDate date) {
        return new ObjectionSubmitRequest("FAILURE", date.toString(), "정전으로 신호가 안 갔습니다", null);
    }

    @Test
    @DisplayName("잠정 실패 일자에 이의 제기 → PENDING 생성")
    void submitCreatesPending() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));

        ObjectionResponse res = objectionService.submit(u.getId(), c.getId(), req(date));
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.type()).isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("솔로 챌린지는 이의 제기 불가 NOT_OBJECTIONABLE")
    void soloNotObjectionable() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.SOLO);
        LocalDate date = LocalDate.now().minusDays(1);
        // 솔로는 방장=본인이 유일 멤버. 잠정 실패 상태를 심어도 solo면 거부.
        ChallengeMember m = memberRepository.findByChallengeIdAndUserId(c.getId(), owner.getId()).orElseThrow();
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));

        assertThatThrownBy(() -> objectionService.submit(owner.getId(), c.getId(), req(date)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_OBJECTIONABLE);
    }

    @Test
    @DisplayName("잠정 실패가 아니면 NOT_OBJECTIONABLE")
    void notProvisionalRejected() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);   // daily 없음(=PENDING 해석)

        assertThatThrownBy(() -> objectionService.submit(u.getId(), c.getId(), req(date)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_OBJECTIONABLE);
    }

    @Test
    @DisplayName("3일 창 종료 후 제출 → OBJECTION_WINDOW_CLOSED")
    void windowClosed() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(4);
        provisional(m, date, Instant.now().minus(1, ChronoUnit.HOURS));   // 창 이미 종료

        assertThatThrownBy(() -> objectionService.submit(u.getId(), c.getId(), req(date)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OBJECTION_WINDOW_CLOSED);
    }

    @Test
    @DisplayName("동일 일자 재제출 → ALREADY_OBJECTED")
    void alreadyObjected() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));
        objectionService.submit(u.getId(), c.getId(), req(date));

        assertThatThrownBy(() -> objectionService.submit(u.getId(), c.getId(), req(date)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_OBJECTED);
    }

    @Test
    @DisplayName("승인 → 해당 일자 SUCCESS(OBJECTION)")
    void approveConfirmsSuccess() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));
        ObjectionResponse o = objectionService.submit(u.getId(), c.getId(), req(date));

        var res = objectionService.decide(owner.getId(), c.getId(),
                UUID.fromString(o.objectionId()), new ObjectionDecisionRequest("APPROVE", null));

        assertThat(res.resultStatus()).isEqualTo("SUCCESS");
        assertThat(res.verifiedVia()).isEqualTo("OBJECTION");
        assertThat(dailyRepo.findByChallengeMemberIdAndTargetDate(m.getId(), date).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.SUCCESS);
    }

    @Test
    @DisplayName("기각 → 해당 일자 FAILED(OBJECTION_REJECTED)")
    void rejectConfirmsFailed() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));
        ObjectionResponse o = objectionService.submit(u.getId(), c.getId(), req(date));

        var res = objectionService.decide(owner.getId(), c.getId(),
                UUID.fromString(o.objectionId()), new ObjectionDecisionRequest("REJECT", "근거 불충분"));

        assertThat(res.resultStatus()).isEqualTo("FAILED");
        VerificationDaily d = dailyRepo.findByChallengeMemberIdAndTargetDate(m.getId(), date).orElseThrow();
        assertThat(d.getStatus()).isEqualTo(VerificationStatus.FAILED);
        assertThat(d.getFailureReason()).isEqualTo("OBJECTION_REJECTED");
    }

    @Test
    @DisplayName("방장/공동 관리자가 아니면 처리 불가 NOT_CHALLENGE_ADMIN")
    void nonAdminCannotDecide() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId());
        LocalDate date = LocalDate.now().minusDays(1);
        provisional(m, date, Instant.now().plus(2, ChronoUnit.DAYS));
        ObjectionResponse o = objectionService.submit(u.getId(), c.getId(), req(date));

        // 제3의 일반 멤버가 처리 시도
        User other = newUser();
        join(c, other.getId());
        assertThatThrownBy(() -> objectionService.decide(other.getId(), c.getId(),
                UUID.fromString(o.objectionId()), new ObjectionDecisionRequest("APPROVE", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_CHALLENGE_ADMIN);
    }
}
