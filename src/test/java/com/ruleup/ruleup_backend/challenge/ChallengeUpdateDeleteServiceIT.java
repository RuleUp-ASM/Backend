package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.DeleteChallengeResponse;
import com.ruleup.ruleup_backend.challenge.dto.UpdateChallengeRequest;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeService;
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

/**
 * 수정(§4)·삭제(§8) 통합 검증.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeUpdateDeleteServiceIT {

    @Autowired ChallengeService challengeService;
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

    // ===== 삭제 =====

    @Test
    @DisplayName("시작 전·참여자 0명 삭제 = 하드 삭제, 무패널티, 자식 행까지 제거")
    void deleteUpcomingHardDeletes() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        UUID ownerMemberId = memberRepository.findByChallengeIdAndUserId(c.getId(), owner.getId()).orElseThrow().getId();
        // 자식 행(VerificationDaily) 하나 심어두고 하드 삭제로 함께 지워지는지 확인.
        verificationDailyRepository.saveAndFlush(
                VerificationDaily.open(ownerMemberId, c.getId(), owner.getId(), LocalDate.now()));

        DeleteChallengeResponse res = challengeService.delete(owner.getId(), c.getId());

        assertThat(res.penaltyApplied()).isFalse();
        assertThat(challengeRepository.findById(c.getId())).isEmpty();
        assertThat(memberRepository.findByChallengeIdAndUserId(c.getId(), owner.getId())).isEmpty();
        assertThat(verificationDailyRepository.findByChallengeMemberIdAndTargetDate(ownerMemberId, LocalDate.now())).isEmpty();
    }

    @Test
    @DisplayName("참여자가 있으면 CHALLENGE_HAS_MEMBERS")
    void deleteFailsWithMembers() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        memberService.join(newUser().getId(), c.getId());

        assertThatThrownBy(() -> challengeService.delete(owner.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_HAS_MEMBERS);
    }

    @Test
    @DisplayName("종료된 챌린지는 CHALLENGE_COMPLETED")
    void deleteFailsWhenCompleted() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        c.activate();
        c.complete();
        challengeRepository.saveAndFlush(c);

        assertThatThrownBy(() -> challengeService.delete(owner.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_COMPLETED);
    }

    @Test
    @DisplayName("진행 중·success 이력 있으면 삭제 + 패널티 트리거")
    void deleteActiveWithSuccessTriggersPenalty() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        c.activate();
        challengeRepository.saveAndFlush(c);
        UUID ownerMemberId = memberRepository.findByChallengeIdAndUserId(c.getId(), owner.getId()).orElseThrow().getId();
        VerificationDaily d = VerificationDaily.open(ownerMemberId, c.getId(), owner.getId(), LocalDate.now());
        d.recordResult(VerificationStatus.SUCCESS, "MANUAL", null, Instant.now());
        verificationDailyRepository.saveAndFlush(d);

        DeleteChallengeResponse res = challengeService.delete(owner.getId(), c.getId());
        assertThat(res.penaltyApplied()).isTrue();
        assertThat(challengeRepository.findById(c.getId())).isEmpty();
    }

    @Test
    @DisplayName("OWNER가 아니면 NOT_CHALLENGE_OWNER")
    void deleteFailsForNonOwner() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);

        assertThatThrownBy(() -> challengeService.delete(newUser().getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_CHALLENGE_OWNER);
    }

    // ===== 수정 =====

    @Test
    @DisplayName("시작 전·멤버 0명이면 전 항목 수정 가능")
    void updateAllFieldsWhenUpcomingAndEmpty() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);

        UpdateChallengeRequest req = new UpdateChallengeRequest(
                "새 제목", null, null, null, null, null, null, null, null, null, null, null);
        challengeService.update(owner.getId(), c.getId(), req);

        assertThat(challengeRepository.findById(c.getId()).orElseThrow().getTitle()).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("멤버가 있으면 인원 상한 외 필드 수정 시 CHALLENGE_NOT_EDITABLE")
    void updateOtherFieldsBlockedWithMembers() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        memberService.join(newUser().getId(), c.getId());

        UpdateChallengeRequest req = new UpdateChallengeRequest(
                "새 제목", null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> challengeService.update(owner.getId(), c.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_NOT_EDITABLE);
    }

    @Test
    @DisplayName("인원 상한은 진행 중에도 수정 가능하나 현재 인원 미만 축소는 불가")
    void updateMaxParticipantsAnytimeButNotBelowCurrent() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        memberService.join(newUser().getId(), c.getId());   // 현재 2명
        c.activate();
        challengeRepository.saveAndFlush(c);

        // 인원 상한 증가는 진행 중에도 허용
        UpdateChallengeRequest raise = new UpdateChallengeRequest(
                null, null, null, null, null, null, null, null, null, null, null, 10);
        challengeService.update(owner.getId(), c.getId(), raise);
        assertThat(challengeRepository.findById(c.getId()).orElseThrow().getMaxParticipants()).isEqualTo(10);

        // 현재 인원(2) 미만 축소는 불가
        UpdateChallengeRequest shrink = new UpdateChallengeRequest(
                null, null, null, null, null, null, null, null, null, null, null, 1);
        assertThatThrownBy(() -> challengeService.update(owner.getId(), c.getId(), shrink))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAX_PARTICIPANTS_BELOW_CURRENT);
    }
}
