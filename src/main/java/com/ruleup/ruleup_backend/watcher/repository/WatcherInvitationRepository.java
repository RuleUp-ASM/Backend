package com.ruleup.ruleup_backend.watcher.repository;

import com.ruleup.ruleup_backend.watcher.domain.WatcherInvitation;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatcherInvitationRepository extends JpaRepository<WatcherInvitation, UUID> {

    /** 수락 시 단건 조회 — 원본이 아니라 해시로 찾는다. */
    Optional<WatcherInvitation> findByTokenHash(String tokenHash);

    /**
     * 만료 처리 배치 — 미수락이면서 만료됐고 아직 알리지 않은 건.
     * 미수락이 극소수라 {@code accepted_at} 을 인덱스 선두에 둔다.
     */
    @Query("""
            select i from WatcherInvitation i
             where i.acceptedAt is null
               and i.expiresAt <= :now
               and i.expiryNotifiedAt is null
            """)
    List<WatcherInvitation> findExpiredUnnotified(@Param("now") Instant now, Limit limit);
}
