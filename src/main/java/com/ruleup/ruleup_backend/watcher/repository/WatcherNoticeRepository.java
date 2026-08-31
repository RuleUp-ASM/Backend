package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherNoticeRepository extends JpaRepository<WatcherNotice, UUID> {

    /** 조기 발송 감사의 핵심 — 확정 시각과 대조할 통지들. */
    List<WatcherNotice> findByVerificationId(UUID verificationId);

    /** 통지 멱등성 — 확정 이벤트가 재전송돼도 같은 건으로 두 번 발송하지 않는다. */
    boolean existsByRelationIdAndVerificationId(UUID relationId, UUID verificationId);

    Optional<WatcherNotice> findByIdAndRelationIdIn(UUID id, List<UUID> relationIds);
}
