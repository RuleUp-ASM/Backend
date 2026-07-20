package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.*;
import com.ruleup.ruleup_backend.challenge.dto.DelegationActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.DelegationResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeDelegationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.challenge.service.ChallengeDelegationService;
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
 * 방장 위임(§7-2) 통합 검증.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ChallengeDelegationServiceIT {

    @Autowired ChallengeDelegationService delegationService;
    @Autowired ChallengeMemberService memberService;
    @Autowired ChallengeRepository challengeRepository;
    @Autowired ChallengeMemberRepository memberRepository;
    @Autowired ChallengeDelegationRepository delegationRepository;
    @Autowired UserRepository userRepository;

    private User newUser() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(User.create(
                OAuthProvider.KAKAO, "sub-" + uniq, null, "u-" + uniq, null, List.of()));
    }

    private Challenge newGroupChallenge(UUID ownerId) {
        Challenge c = Challenge.create(
                ownerId, "그룹 챌린지", null, null,
                "EXERCISE", ParticipationType.GROUP, null, 10, List.of("MON"),
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

    /** owner + manager 셋업된 챌린지 반환. */
    private record Setup(Challenge c, User owner, User manager) {}

    private Setup withManager() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId());
        User manager = newUser();
        memberService.join(manager.getId(), c.getId());
        memberService.changeRole(owner.getId(), c.getId(), manager.getId(), "PROMOTE");
        return new Setup(c, owner, manager);
    }

    @Test
    @DisplayName("MANAGER 대상 위임 요청 → PENDING 생성")
    void requestCreatesPending() {
        Setup s = withManager();
        DelegationResponse res = delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId());
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.expiresAt()).isNotBlank();
    }

    @Test
    @DisplayName("대상이 MANAGER 가 아니면 TARGET_NOT_MANAGER")
    void requestFailsWhenTargetNotManager() {
        User owner = newUser();
        Challenge c = newGroupChallenge(owner.getId());
        User member = newUser();
        memberService.join(member.getId(), c.getId());   // MEMBER

        assertThatThrownBy(() -> delegationService.request(owner.getId(), c.getId(), member.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TARGET_NOT_MANAGER);
    }

    @Test
    @DisplayName("유효 요청이 있으면 DELEGATION_ALREADY_PENDING")
    void requestFailsWhenPendingExists() {
        Setup s = withManager();
        delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId());

        assertThatThrownBy(() -> delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DELEGATION_ALREADY_PENDING);
    }

    @Test
    @DisplayName("ACCEPT 시 role swap — 새 OWNER 확정, 기존 OWNER 는 MEMBER, OWNER 정확히 1명")
    void acceptSwapsOwner() {
        Setup s = withManager();
        DelegationResponse req = delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId());

        DelegationActionResponse res = delegationService.respond(
                s.manager().getId(), s.c().getId(), UUID.fromString(req.delegationId()), "ACCEPT");

        assertThat(res.status()).isEqualTo("ACCEPTED");
        assertThat(res.newOwnerUserId()).isEqualTo(s.manager().getId().toString());

        Challenge reloaded = challengeRepository.findById(s.c().getId()).orElseThrow();
        assertThat(reloaded.getCreatorId()).isEqualTo(s.manager().getId());
        assertThat(memberRepository.findByChallengeIdAndUserId(s.c().getId(), s.manager().getId())
                .orElseThrow().getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(memberRepository.findByChallengeIdAndUserId(s.c().getId(), s.owner().getId())
                .orElseThrow().getRole()).isEqualTo(MemberRole.MEMBER);
        long owners = memberRepository.findByChallengeIdOrderByJoinedAtAsc(s.c().getId()).stream()
                .filter(ChallengeMember::isOwner).count();
        assertThat(owners).isEqualTo(1);
    }

    @Test
    @DisplayName("대상자가 아니면 ACCEPT 시 NOT_DELEGATION_TARGET")
    void acceptByNonTargetFails() {
        Setup s = withManager();
        DelegationResponse req = delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId());

        assertThatThrownBy(() -> delegationService.respond(
                s.owner().getId(), s.c().getId(), UUID.fromString(req.delegationId()), "ACCEPT"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_DELEGATION_TARGET);
    }

    @Test
    @DisplayName("요청자(OWNER)는 CANCEL, 이미 처리된 요청 재응답은 DELEGATION_ALREADY_RESOLVED")
    void cancelThenAlreadyResolved() {
        Setup s = withManager();
        DelegationResponse req = delegationService.request(s.owner().getId(), s.c().getId(), s.manager().getId());

        DelegationActionResponse cancel = delegationService.respond(
                s.owner().getId(), s.c().getId(), UUID.fromString(req.delegationId()), "CANCEL");
        assertThat(cancel.status()).isEqualTo("CANCELED");

        assertThatThrownBy(() -> delegationService.respond(
                s.manager().getId(), s.c().getId(), UUID.fromString(req.delegationId()), "ACCEPT"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DELEGATION_ALREADY_RESOLVED);
    }
}
