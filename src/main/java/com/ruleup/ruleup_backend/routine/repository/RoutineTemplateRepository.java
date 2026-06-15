package com.ruleup.ruleup_backend.routine.repository;

import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 루틴 템플릿 조회. 템플릿은 105개 수준의 정적 카탈로그라
 * 전체를 한 번 읽어 메모리에 캐시해 쓴다(RoutineCatalog). 매칭 경로에서 매번 DB 안 친다.
 */
public interface RoutineTemplateRepository extends JpaRepository<RoutineTemplate, Long> {
}