package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.Appeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 인증 이의 접근. 접수된 행은 전부 인용된 이의라 상태 조건이 없다. */
public interface AppealRepository extends JpaRepository<Appeal, UUID> {

    /** 실패 결과 기준 멱등: 같은 인증에 이미 이의가 있는지. */
    Optional<Appeal> findByVerificationDailyId(UUID verificationDailyId);

    boolean existsByVerificationDailyId(UUID verificationDailyId);

    /** 마이페이지 이의 현황: 내가 낸 이의 전건(최신순). 전건이 인용이라 상태 조건이 없다. */
    List<Appeal> findByUserIdOrderByAcceptedAtDesc(UUID userId);

    /** 이상탐지 입력: 그 사용자의 최근 이의 이력(빈도·반복 사유·동일 이미지 판정용). */
    List<Appeal> findByUserIdAndAcceptedAtGreaterThanEqualOrderByAcceptedAtDesc(UUID userId, Instant since);
}
