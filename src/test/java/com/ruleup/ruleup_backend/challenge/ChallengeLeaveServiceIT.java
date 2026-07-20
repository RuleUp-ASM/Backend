package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.LeaveResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 탈퇴(§6) 통합 검증.
 *  - 본인 success 없음=무패널티 / 있음=패널티 트리거 / OWNER 불가(사유 DELEGATE_FIRST·DELETE_INSTEAD) / 종료 불가 / 재참여 금지.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeLeaveServiceIT {

    @Autowired ChallengeMemberService memberService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;
    @Autowired VerificationDailyRepository verificationDailyRepository;

    private User newUser() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + uniq, null, "u-" + uniq, null, List.of()));
    }

    private Challenge newGroupChallenge(UUID ownerId, int cap) {
        Challenge c = Challenge.create(
                ownerId, "그룹 챌린지", null, null,
                "EXERCISE", ParticipationType.GROUP, null, cap, List.of("MON"),
                14, LocalDate.now(),
                null, VerificationConfig.manual(null), new LinkedHashMap<>(),
                new PenaltyConfig(BigDecimal.ONE, null, false), new RewardConfig(BigDecimal.ONE),
                Anonymity.REAL, false);
        challengeRepository.saveAndFlush(c);
        memberRepository.saveAndFlush(ChallengeMember.owner(c.getId(), ownerId));
        c.increaseParticipantCount();
        challengeRepository.saveAndFlush(c);
        return c;
    }

    @Test
    @DisplayName("success 이력 없는 멤버 탈퇴 = 무패널티, LEFT 전이 + 참여자 수 감소")
    void leaveWithoutSuccessNoPenalty() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User member = newUser();
        memberService.join(member.getId(), c.getId());

        LeaveResponse res = memberService.leave(member.getId(), c.getId());

        assertThat(res.penaltyApplied()).isFalse();
        assertThat(memberRepository.findByChallengeIdAndUserId(c.getId(), member.getId())
                .orElseThrow().getStatus()).isEqualTo(MemberStatus.LEFT);
        assertThat(challengeRepository.findById(c.getId()).orElseThrow().getParticipantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("success 이력 있는 멤버 탈퇴 = 패널티 트리거")
    void leaveWithSuccessTriggersPenalty() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User member = newUser();
        memberService.join(member.getId(), c.getId());
        UUID memberId = memberRepository.findByChallengeIdAndUserId(c.getId(), member.getId()).orElseThrow().getId();

        VerificationDaily d = VerificationDaily.open(memberId, c.getId(), member.getId(), LocalDate.now());
        d.recordResult(VerificationStatus.SUCCESS, "MANUAL", null, Instant.now());
        verificationDailyRepository.saveAndFlush(d);

        assertThat(memberService.leave(member.getId(), c.getId()).penaltyApplied()).isTrue();
    }

    @Test
    @DisplayName("OWNER는 탈퇴 불가 — 참여자 있으면 DELEGATE_FIRST")
    void ownerCannotLeaveWithMembers() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        memberService.join(newUser().getId(), c.getId());

        BusinessException ex = catchThrowableOfType(
                () -> memberService.leave(owner.getId(), c.getId()), BusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OWNER_CANNOT_LEAVE);
        assertThat(ex.getDetail()).isEqualTo("DELEGATE_FIRST");
    }

    @Test
    @DisplayName("OWNER는 탈퇴 불가 — 참여자 0명이면 DELETE_INSTEAD")
    void ownerCannotLeaveAlone() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);

        BusinessException ex = catchThrowableOfType(
                () -> memberService.leave(owner.getId(), c.getId()), BusinessException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OWNER_CANNOT_LEAVE);
        assertThat(ex.getDetail()).isEqualTo("DELETE_INSTEAD");
    }

    @Test
    @DisplayName("종료된 챌린지는 탈퇴 불가 CHALLENGE_COMPLETED")
    void leaveFailsWhenCompleted() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User member = newUser();
        memberService.join(member.getId(), c.getId());
        c.activate();
        c.complete();
        challengeRepository.saveAndFlush(c);

        assertThatThrownBy(() -> memberService.leave(member.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_COMPLETED);
    }

    @Test
    @DisplayName("멤버가 아니면 MEMBER_NOT_FOUND")
    void leaveFailsWhenNotMember() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);

        assertThatThrownBy(() -> memberService.leave(newUser().getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("탈퇴 후 재참여 시도는 REJOIN_FORBIDDEN")
    void rejoinForbiddenAfterLeave() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User member = newUser();
        memberService.join(member.getId(), c.getId());
        memberService.leave(member.getId(), c.getId());

        assertThatThrownBy(() -> memberService.join(member.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REJOIN_FORBIDDEN);
    }
}
