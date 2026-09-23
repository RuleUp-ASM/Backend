package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.SignalExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 배제 로그. 쓰기는 인증 경로가, 읽기는 이상탐지 집계가 한다. */
public interface SignalExclusionRepository extends JpaRepository<SignalExclusion, UUID> {

    /** 이상탐지 입력 — {@code idx_exclusion_anomaly (userId, excludedAt, reason)} 를 탄다. */
    List<SignalExclusion> findByUserIdAndExcludedAtGreaterThanEqual(UUID userId, Instant since);

    /** 한 판정에 달린 배제. 판정 근거를 설명할 때 「무엇이 빠졌는지」를 보여준다. */
    List<SignalExclusion> findByVerificationDailyId(UUID verificationDailyId);
}
