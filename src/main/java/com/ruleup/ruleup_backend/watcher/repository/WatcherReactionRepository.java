package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherReaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WatcherReactionRepository
        extends JpaRepository<WatcherReaction, WatcherReaction.Key> {

    boolean existsByNoticeIdAndWatcherUserId(UUID noticeId, UUID watcherUserId);
}
