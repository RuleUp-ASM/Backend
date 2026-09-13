package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.CheatDetection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 검출 이력. 지우지 않고 계정에 누적 보존한다. */
public interface CheatDetectionRepository extends JpaRepository<CheatDetection, UUID> {

    /** 같은 판정으로 두 번 확정하지 않기 위한 멱등 조회 — UNIQUE 가 최종 보증이다. */
    Optional<CheatDetection> findByVerificationDailyId(UUID verificationDailyId);

    /** 마이페이지 검출 이력 · 운영자의 상습성 판단 — {@code idx_cheat_user}. */
    List<CheatDetection> findByUserIdOrderByDetectedAtDesc(UUID userId);

    /** 해당 챌린지 영구 차단 판정 — 가입 게이트가 본다. */
    boolean existsByChallengeIdAndUserId(UUID challengeId, UUID userId);
}
