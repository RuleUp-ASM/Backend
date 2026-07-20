package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가입(§5) 통합 검증 — 실제 MySQL(Testcontainers).
 *  - 승인 없이 즉시 ACTIVE / 정원 초과 CHALLENGE_FULL / 재참여 금지 REJOIN_FORBIDDEN / 종료 CHALLENGE_COMPLETED.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeJoinServiceIT {

    @Autowired ChallengeMemberService memberService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + uniq, null, "u-" + uniq, null, List.of()));
    }

    /** GROUP 챌린지(이미지 없음=모더레이션 NONE) + 방장 ACTIVE 1명 등록. */
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
    @DisplayName("가입은 승인 없이 즉시 ACTIVE 로 확정된다")
    void joinBecomesActiveImmediately() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User joiner = newUser();

        JoinResponse res = memberService.join(joiner.getId(), c.getId());

        assertThat(res.memberStatus()).isEqualTo("ACTIVE");
        ChallengeMember row = memberRepository.findByChallengeIdAndUserId(c.getId(), joiner.getId()).orElseThrow();
        assertThat(row.isActive()).isTrue();
    }

    @Test
    @DisplayName("정원이 가득 차면 CHALLENGE_FULL")
    void joinFailsWhenFull() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 2);   // 방장 포함 정원 2
        memberService.join(newUser().getId(), c.getId());     // 두 번째 자리 채움

        assertThatThrownBy(() -> memberService.join(newUser().getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_FULL);
    }

    @Test
    @DisplayName("탈퇴 이력(LEFT)이 있으면 재참여 금지 REJOIN_FORBIDDEN")
    void rejoinForbiddenAfterLeave() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        User user = newUser();
        // 탈퇴 이력 시뮬레이션: LEFT 멤버 행을 심어둔다.
        ChallengeMember left = ChallengeMember.join(c.getId(), user.getId(), MemberStatus.LEFT);
        memberRepository.saveAndFlush(left);

        assertThatThrownBy(() -> memberService.join(user.getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REJOIN_FORBIDDEN);
    }

    @Test
    @DisplayName("종료된 챌린지는 CHALLENGE_COMPLETED")
    void joinFailsWhenCompleted() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId(), 5);
        c.activate();
        c.complete();
        challengeRepository.saveAndFlush(c);

        assertThatThrownBy(() -> memberService.join(newUser().getId(), c.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CHALLENGE_COMPLETED);
    }
}
