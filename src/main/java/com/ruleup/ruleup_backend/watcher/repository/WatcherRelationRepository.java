package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherRelation;
import com.ruleup.ruleup_backend.watcher.domain.WatcherRelationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherRelationRepository extends JpaRepository<WatcherRelation, UUID> {

    /** Consent-bearing active relations, serialized with removal and dispatch. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from WatcherRelation r
             where r.challengeId = :challengeId
               and r.targetUserId = :targetUserId
               and r.status = com.ruleup.ruleup_backend.watcher.domain.WatcherRelationStatus.ACTIVE
               and r.removedAt is null
               and r.acceptedAt is not null
               and r.consentVersion is not null
            """)
    List<WatcherRelation> findDispatchTargets(@Param("challengeId") UUID challengeId,
                                              @Param("targetUserId") UUID targetUserId);

    /** 3중 유니크 — 수락 처리가 멱등해지는 근거. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<WatcherRelation> findByChallengeIdAndTargetUserIdAndWatcherUserId(
            UUID challengeId, UUID targetUserId, UUID watcherUserId);

    /** 마이페이지 패널티 수신 관리 — 내가 감시자로 등록된 관계. */
    List<WatcherRelation> findByWatcherUserIdAndRemovedAtIsNull(UUID watcherUserId);

    Optional<WatcherRelation> findByIdAndWatcherUserId(UUID id, UUID watcherUserId);

    /** 피감시자가 보는 내 감시자 목록. */
    List<WatcherRelation> findByChallengeIdAndTargetUserIdAndRemovedAtIsNull(
            UUID challengeId, UUID targetUserId);

    /** 슬롯 계산 — 이 챌린지에서 내가 지정한 살아 있는 관계 수. */
    long countByChallengeIdAndTargetUserIdAndRemovedAtIsNull(UUID challengeId, UUID targetUserId);

    /** 루틴 종료 자동 제거 배치. */
    @Query("""
            select r from WatcherRelation r
             where r.challengeId in :challengeIds
               and r.removedAt is null
            """)
    List<WatcherRelation> findLiveByChallengeIds(@Param("challengeIds") List<UUID> challengeIds);

    /**
     * 감사용 — 종료된 루틴에 잔존하는 ACTIVE 관계. 상시 0이어야 하며
     * 누적되면 자동 제거 배치가 죽은 것이다.
     */
    long countByStatusAndRemovedAtIsNullAndInvitedAtBefore(WatcherRelationStatus status, Instant before);
    @Query("""
            select r from WatcherRelation r where r.removedAt is null and exists
            (select c.id from Challenge c where c.id = r.challengeId and
             (c.status = com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus.COMPLETED or c.deletedAt is not null))
            """)
    List<WatcherRelation> findFinishedRelations();
}
