package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WatcherBlockRepository extends JpaRepository<WatcherBlock, UUID> {

    /** 현재 유효한 차단이 있는지(생성자–대상 단위, 30일). */
    boolean existsByInviterUserIdAndSubjectKeyAndBlockedUntilAfter(
            UUID inviterUserId, String subjectKey, Instant now);

    /** 현재 유효한 차단 중 가장 늦게 풀리는 것(blockedUntil 안내용). */
    Optional<WatcherBlock> findTopByInviterUserIdAndSubjectKeyAndBlockedUntilAfterOrderByBlockedUntilDesc(
            UUID inviterUserId, String subjectKey, Instant now);
}
