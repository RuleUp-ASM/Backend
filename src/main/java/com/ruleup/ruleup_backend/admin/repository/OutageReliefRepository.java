package com.ruleup.ruleup_backend.admin.repository;

import com.ruleup.ruleup_backend.admin.domain.OutageRelief;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutageReliefRepository extends JpaRepository<OutageRelief, UUID> {

    /** 판정 건이 구제 범위에 드는지 역조회 — 인증 모듈이 사용한다. */
    @Query("""
            select r from OutageRelief r
             where r.periodStart <= :at and r.periodEnd >= :at
            """)
    List<OutageRelief> findCovering(@Param("at") Instant at);
}
