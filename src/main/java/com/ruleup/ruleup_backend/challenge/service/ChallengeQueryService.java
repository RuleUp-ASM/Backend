package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeMemberRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 챌린지 집계(challenge·member) 조회 파사드.
 *
 * <p>다른 도메인(예: verification)이 challenge 리포지토리를 직접 들여다보지 않고 이 파사드만 의존하도록 한다.
 * (계층 경계 유지 — 외부에서 challenge 내부 영속 계층에 결합하지 않음)
 *
 * <p>모든 메서드는 호출자의 트랜잭션 안에서 실행된다(별도 트랜잭션을 열지 않음).
 */
@Service
@RequiredArgsConstructor
public class ChallengeQueryService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository memberRepository;

    /** 챌린지 1건(소프트 삭제 포함). */
    public Optional<Challenge> findChallenge(UUID challengeId) {
        return challengeRepository.findById(challengeId);
    }

    /** 챌린지 1건(소프트 삭제 제외). */
    public Optional<Challenge> findActiveChallenge(UUID challengeId) {
        return challengeRepository.findByIdAndDeletedAtIsNull(challengeId);
    }

    /** 멤버십 1건(PK). */
    public Optional<ChallengeMember> findMember(UUID memberId) {
        return memberRepository.findById(memberId);
    }

    /** 특정 챌린지의 특정 사용자 멤버십. */
    public Optional<ChallengeMember> findMembership(UUID challengeId, UUID userId) {
        return memberRepository.findByChallengeIdAndUserId(challengeId, userId);
    }

    /** 내 모든 멤버십(상태 무관). */
    public List<ChallengeMember> findAllMemberships(UUID userId) {
        return memberRepository.findByUserId(userId);
    }

    /** 내 ACTIVE 멤버십. */
    public List<ChallengeMember> findActiveMemberships(UUID userId) {
        return memberRepository.findByUserIdAndStatus(userId, MemberStatus.ACTIVE);
    }

    /** 빈도형 주기 롤오버 대상: 현재 주기가 끝난 ACTIVE 멤버. */
    public List<ChallengeMember> findFrequencyRolloverTargets(LocalDate date) {
        return memberRepository.findByScheduleTypeAndStatusAndCurPeriodEndLessThan(
                ScheduleType.FREQUENCY, MemberStatus.ACTIVE, date);
    }

    /** 멤버십 비정규화 상태(인증 진행률·셋업·앵커 등) 저장. */
    public ChallengeMember saveMember(ChallengeMember member) {
        return memberRepository.save(member);
    }

    /**
     * 방장(OWNER)인지 — 공동 관리자 역할은 폐기됐으므로 승인 권한은 방장에게만 있다.
     */
    public boolean isChallengeAdmin(Challenge challenge, UUID userId) {
        return challenge.isOwner(userId);
    }
}
