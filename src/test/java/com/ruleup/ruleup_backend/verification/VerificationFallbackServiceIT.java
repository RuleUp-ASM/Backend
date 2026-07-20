package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.dto.FallbackApprovalRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.VerificationApprovalService;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예비 폴백 v3(§10.2): 솔로 즉시 SUCCESS / 그룹 승인 / 월3회 한도 / content 필수 / 기각=자동 경로 복귀.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VerificationFallbackServiceIT {

    @Autowired VerificationManualService manualService;
    @Autowired VerificationApprovalService approvalService;
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
        Challenge c = Challenge.create(ownerId, "챌린지", null, null, "EXERCISE", type, null, cap,
                List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
                14, LocalDate.now(), null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        c.activate();
        challengeRepository.saveAndFlush(c);
        memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), ownerId));
        return c;
    }

    private ChallengeMember join(Challenge c, UUID userId, MemberRole role) {
        ChallengeMember m = ChallengeMember.join(c.getId(), userId, MemberStatus.ACTIVE);
        if (role == MemberRole.MANAGER) m.changeRole(MemberRole.MANAGER);
        return memberRepository.saveAndFlush(m);
    }

    private ManualVerificationRequest fallback(LocalDate date, String content) {
        return new ManualVerificationRequest("SELF_CHECK", date.toString(), null, content, true);
    }

    @Test
    @DisplayName("솔로 폴백은 글 포함 제출 즉시 SUCCESS(MANUAL_FALLBACK)")
    void soloFallbackImmediate() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.SOLO);
        ManualVerificationResponse res = manualService.submit(owner.getId(), c.getId(),
                fallback(LocalDate.now(), "기기 오류로 수동 제출"));
        assertThat(res.status()).isEqualTo("SUCCESS");
        assertThat(res.verifiedVia()).isEqualTo("MANUAL_FALLBACK");
    }

    @Test
    @DisplayName("그룹 폴백은 PENDING_APPROVAL, 글 없으면 CONTENT_REQUIRED")
    void groupFallbackPendingAndContentRequired() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        join(c, u.getId(), MemberRole.MEMBER);

        assertThatThrownBy(() -> manualService.submit(u.getId(), c.getId(), fallback(LocalDate.now(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONTENT_REQUIRED);

        ManualVerificationResponse res = manualService.submit(u.getId(), c.getId(),
                fallback(LocalDate.now(), "정전으로 자동 인증 실패"));
        assertThat(res.status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("월 3회 초과 시 FALLBACK_LIMIT_EXCEEDED")
    void monthlyLimit() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        join(c, u.getId(), MemberRole.MEMBER);
        for (int i = 0; i < 3; i++) {
            manualService.submit(u.getId(), c.getId(), fallback(LocalDate.now().plusDays(i), "글" + i));
        }
        assertThatThrownBy(() -> manualService.submit(u.getId(), c.getId(), fallback(LocalDate.now().plusDays(3), "초과")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FALLBACK_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("공동 관리자(MANAGER)도 그룹 폴백을 승인할 수 있다 → SUCCESS(MANUAL_FALLBACK)")
    void managerApproves() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User mgr = newUser();
        join(c, mgr.getId(), MemberRole.MANAGER);
        User u = newUser();
        join(c, u.getId(), MemberRole.MEMBER);
        LocalDate date = LocalDate.now();
        ManualVerificationResponse sub = manualService.submit(u.getId(), c.getId(), fallback(date, "정전"));

        var res = approvalService.decide(mgr.getId(), c.getId(),
                UUID.fromString(sub.verificationId()), new FallbackApprovalRequest("APPROVE", null));

        assertThat(res.status()).isEqualTo("SUCCESS");
        assertThat(res.verifiedVia()).isEqualTo("MANUAL_FALLBACK");
    }

    @Test
    @DisplayName("기각은 일자 실패 확정이 아니라 PENDING 으로 복귀(자동 경로 유지)")
    void rejectReturnsToPending() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        ChallengeMember m = join(c, u.getId(), MemberRole.MEMBER);
        LocalDate date = LocalDate.now();
        ManualVerificationResponse sub = manualService.submit(u.getId(), c.getId(), fallback(date, "정전"));

        var res = approvalService.decide(owner.getId(), c.getId(),
                UUID.fromString(sub.verificationId()), new FallbackApprovalRequest("REJECT", "근거 불충분"));

        assertThat(res.status()).isEqualTo("REJECTED");
        assertThat(dailyRepo.findByChallengeMemberIdAndTargetDate(m.getId(), date).orElseThrow().getStatus())
                .isEqualTo(VerificationStatus.PENDING);   // 실패 확정 아님
    }

    @Test
    @DisplayName("방장/공동 관리자가 아니면 승인 불가 NOT_CHALLENGE_ADMIN")
    void nonAdminCannotApprove() {
        User owner = newUser();
        Challenge c = newChallenge(owner.getId(), ParticipationType.GROUP);
        User u = newUser();
        join(c, u.getId(), MemberRole.MEMBER);
        ManualVerificationResponse sub = manualService.submit(u.getId(), c.getId(), fallback(LocalDate.now(), "정전"));
        User other = newUser();
        join(c, other.getId(), MemberRole.MEMBER);

        assertThatThrownBy(() -> approvalService.decide(other.getId(), c.getId(),
                UUID.fromString(sub.verificationId()), new FallbackApprovalRequest("APPROVE", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_CHALLENGE_ADMIN);
    }
}
