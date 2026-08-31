package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherConsentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 분쟁 시 입증 자료 — 관계별 시간순 조회만 하면 된다. */
public interface WatcherConsentLogRepository extends JpaRepository<WatcherConsentLog, UUID> {

    List<WatcherConsentLog> findByRelationIdOrderByOccurredAtAsc(UUID relationId);
}
