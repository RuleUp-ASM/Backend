package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.TemplateStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** TemplateStats 접근. 배치 재작성 + 탐색 목록 조인용 일괄 조회. */
public interface TemplateStatsRepository extends JpaRepository<TemplateStats, Long> {

    List<TemplateStats> findByTemplateIdIn(java.util.Collection<Long> templateIds);
}
