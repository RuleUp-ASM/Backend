package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.Watcher;
import com.ruleup.ruleup_backend.watcher.domain.WatcherStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherRepository extends JpaRepository<Watcher, UUID> {

    Optional<Watcher> findByInvitationId(UUID invitationId);

    Optional<Watcher> findByUnsubscribeToken(String unsubscribeToken);

    /** 목록(생성자): 상태 무관 전체(초대 발급 순). */
    List<Watcher> findByChallengeIdOrderByInvitedAtAsc(UUID challengeId);

    /** 목록(생성자): 상태 필터(ACTIVE/INVITED 등). */
    List<Watcher> findByChallengeIdAndStatusOrderByInvitedAtAsc(UUID challengeId, WatcherStatus status);
}
