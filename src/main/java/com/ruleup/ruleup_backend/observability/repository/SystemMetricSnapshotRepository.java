package com.ruleup.ruleup_backend.observability.repository;

import com.ruleup.ruleup_backend.observability.domain.SystemMetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** 시스템 지표 스냅샷 접근. 적재 + 보관기간 정리. */
public interface SystemMetricSnapshotRepository extends JpaRepository<SystemMetricSnapshot, UUID> {

    /** 보관기간 초과분 삭제(정리 배치). */
    @Modifying
    @Query("DELETE FROM SystemMetricSnapshot s WHERE s.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") Instant cutoff);
}
