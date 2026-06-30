package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.InvitationStatus;
import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherInvitationRepository extends JpaRepository<WatcherInvitation, UUID> {

    Optional<WatcherInvitation> findByToken(String token);

    /** 정원(무료 3명) 검사: 살아있는 초대 수(INVITED/CONSENTED). */
    long countByChallengeIdAndStatusIn(UUID challengeId, Collection<InvitationStatus> statuses);

    /** 목록 조립 시 만료시각 매핑용(challengeId 일괄 조회). */
    List<WatcherInvitation> findByChallengeId(UUID challengeId);
}
