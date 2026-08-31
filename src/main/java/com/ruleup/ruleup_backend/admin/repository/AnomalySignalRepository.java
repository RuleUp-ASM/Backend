package com.ruleup.ruleup_backend.admin.repository;

import com.ruleup.ruleup_backend.admin.domain.AnomalySignal;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AnomalySignalRepository extends JpaRepository<AnomalySignal, UUID> {

    /** 미검토 신호를 강도순으로 꺼내는 대시보드 주 쿼리. */
    @Query("""
            select s from AnomalySignal s
             where s.reviewedAt is null
             order by s.score desc, s.detectedAt asc
            """)
    List<AnomalySignal> findUnreviewed(Limit limit);

    /** 유저 통합 뷰의 이상탐지 이력 섹션. */
    List<AnomalySignal> findByTargetUserIdOrderByDetectedAtDesc(UUID targetUserId);
}
