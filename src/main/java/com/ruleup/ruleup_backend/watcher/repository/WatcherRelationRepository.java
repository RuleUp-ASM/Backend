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

    /**
     * <b>발송 대상 조회</b> — 이 쿼리가 "PENDING 발송 0건" 가드레일의 실행부다.
     *
     * <p>{@code status = ACTIVE} · 미제거 · 토글 ON 세 조건을 인덱스
     * {@code (challenge_id, target_user_id, status, push_enabled)} 안에서 끝낸다.
     */
    @Query("""
            select r from WatcherRelation r
             where r.challengeId = :challengeId
               and r.targetUserId = :targetUserId
               and r.status = com.ruleup.ruleup_backend.watcher.domain.WatcherRelationStatus.ACTIVE
               and r.removedAt is null
               and r.pushEnabled = true
            """)
    List<WatcherRelation> findDispatchTargets(@Param("challengeId") UUID challengeId,
                                              @Param("targetUserId") UUID targetUserId);

    /** 3중 유니크 — 수락 처리가 멱등해지는 근거. */
    Optional<WatcherRelation> findByChallengeIdAndTargetUserIdAndWatcherUserId(
            UUID challengeId, UUID targetUserId, UUID watcherUserId);

    /** 마이페이지 패널티 수신 관리 — 내가 감시자로 등록된 관계. */
    List<WatcherRelation> findByWatcherUserIdAndRemovedAtIsNull(UUID watcherUserId);

    Optional<WatcherRelation> findByIdAndWatcherUserId(UUID id, UUID watcherUserId);

    /** 피감시자가 보는 내 감시자 목록. */
    List<WatcherRelation> findByChallengeIdAndTargetUserIdAndRemovedAtIsNull(
            UUID challengeId, UUID targetUserId);

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
}
