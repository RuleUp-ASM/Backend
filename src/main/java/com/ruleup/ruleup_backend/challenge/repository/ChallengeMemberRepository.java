package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ruleup.ruleup_backend.verification.domain.ScheduleType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** challenge_members 접근. */
public interface ChallengeMemberRepository extends JpaRepository<ChallengeMember, UUID> {

    /** 특정 챌린지의 특정 사용자 멤버십 (참여 중복 검사·승인/거절 대상 조회) */
    Optional<ChallengeMember> findByChallengeIdAndUserId(UUID challengeId, UUID userId);

    /** 이미 해당 챌린지에 멤버십이 존재하는지(상태 무관) */
    boolean existsByChallengeIdAndUserId(UUID challengeId, UUID userId);

    /** 상태별 멤버 목록 (3.8 status=ACTIVE/PENDING 필터) */
    List<ChallengeMember> findByChallengeIdAndStatusOrderByJoinedAtAsc(UUID challengeId, MemberStatus status);

    /** 전체 멤버 목록 (3.8 status=ALL, OWNER 전용) */
    List<ChallengeMember> findByChallengeIdOrderByJoinedAtAsc(UUID challengeId);

    /** ACTIVE 멤버 수 (participant_count 정합성 검증·재계산용) */
    long countByChallengeIdAndStatus(UUID challengeId, MemberStatus status);

    /** 내 멤버십 중 상태별 (인증 sync: ACTIVE 챌린지 추림) */
    List<ChallengeMember> findByUserIdAndStatus(UUID userId, MemberStatus status);

    /** 빈도형 주기 롤오버 대상: 현재 주기가 끝난 ACTIVE 멤버. */
    List<ChallengeMember> findByScheduleTypeAndStatusAndCurPeriodEndLessThan(
            ScheduleType scheduleType, MemberStatus status, LocalDate date);
}