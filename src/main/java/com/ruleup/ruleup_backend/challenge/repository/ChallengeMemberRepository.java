package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.ChallengeMember;
import com.ruleup.ruleup_backend.challenge.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import java.time.LocalDate;
import java.util.Collection;
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

    /** 내 모든 멤버십(상태 무관) — 진행률 status=ALL */
    List<ChallengeMember> findByUserId(UUID userId);

    /**
     * 멤버 상태 원자적 전이(CAS): 현재 status가 {@code from} 중 하나일 때만 {@code to}로 변경.
     * 반환값(영향 행 수)이 1이면 이 호출이 전이를 성사시킨 것 → 참여자 수 증감 등 후속 처리를 1회만 수행.
     * 동시 요청은 행 잠금으로 직렬화되어, 뒤늦은 호출은 0행을 받아 중복 처리를 막는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChallengeMember m SET m.status = :to WHERE m.id = :id AND m.status IN :from")
    int compareAndSetStatus(@Param("id") UUID id,
                            @Param("from") Collection<MemberStatus> from,
                            @Param("to") MemberStatus to);
}