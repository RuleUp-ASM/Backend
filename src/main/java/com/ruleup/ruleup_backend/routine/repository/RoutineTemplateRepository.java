package com.ruleup.ruleup_backend.routine.repository;

import com.ruleup.ruleup_backend.routine.domain.RoutineTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 루틴 템플릿 조회. 템플릿은 105개 수준의 정적 카탈로그라
 * 전체를 한 번 읽어 메모리에 캐시해 쓴다(RoutineCatalog). 매칭 경로에서 매번 DB 안 친다.
 */
public interface RoutineTemplateRepository extends JpaRepository<RoutineTemplate, Long> {

    /**
     * 인증 정의(RoutineVerification)까지 한 방에 fetch join 으로 로드.
     * 캐시에 detached 로 올라간 뒤에도 위임 게터가 안전하도록(LazyInit 방지) 카탈로그 초기 적재에 쓴다.
     */
    @Query("select t from RoutineTemplate t left join fetch t.verification order by t.id asc")
    List<RoutineTemplate> findAllWithVerification();
}