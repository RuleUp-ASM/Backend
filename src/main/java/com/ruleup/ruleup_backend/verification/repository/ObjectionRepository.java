package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.Objection;
import com.ruleup.ruleup_backend.verification.domain.ObjectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 이의 제기(§8.7) 접근. */
public interface ObjectionRepository extends JpaRepository<Objection, UUID> {

    Optional<Objection> findByIdAndChallengeId(UUID id, UUID challengeId);

    /** 상세 조회에서 그 날짜의 기제출 이의(있으면). */
    Optional<Objection> findByChallengeMemberIdAndTargetDate(UUID challengeMemberId, LocalDate targetDate);

    /** 동일 일자 재제출 차단(일자당 1회) — 멤버×일자 이의 제기 존재 여부. */
    boolean existsByChallengeMemberIdAndTargetDate(UUID challengeMemberId, LocalDate targetDate);

    /** 처리 대기함(§pending-reviews): 챌린지의 PENDING 이의 제기(제출 시각 오름차순). */
    List<Objection> findByChallengeIdAndStatusOrderByCreatedAtAsc(UUID challengeId, ObjectionStatus status);
}
