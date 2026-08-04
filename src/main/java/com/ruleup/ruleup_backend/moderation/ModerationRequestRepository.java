package com.ruleup.ruleup_backend.moderation;

import com.ruleup.ruleup_backend.moderation.domain.ModerationRequest;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequestStatus;
import com.ruleup.ruleup_backend.moderation.domain.ModerationTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModerationRequestRepository extends JpaRepository<ModerationRequest, UUID> {

    List<ModerationRequest> findByUserIdAndTarget(UUID userId, ModerationTarget target);

    /** 사용자별 target 하나에 PENDING 은 하나만 존재(UNIQUE) — 심사 처리 대상 조회. */
    Optional<ModerationRequest> findByUserIdAndTargetAndStatus(
            UUID userId, ModerationTarget target, ModerationRequestStatus status);
}
