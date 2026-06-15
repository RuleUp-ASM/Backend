package com.ruleup.ruleup_backend.routine.match;

import java.util.List;

/**
 * 제목/설명을 후보 템플릿 중 하나로 매칭하고 목표값을 뽑아오는 LLM 클라이언트.
 *
 * 구현체를 빈으로 1개만 등록해서 갈아끼운다(현재 {@link SolarRoutineMatchClient}).
 * 실패(타임아웃/파싱오류 등)는 RuntimeException 으로 던지고, 서비스가 잡아 "수동 인증"으로 폴백한다.
 * → 추천 API 는 LLM 이 죽어도 사용자에게 항상 무언가(수동)를 돌려준다.
 */
public interface RoutineMatchClient {

    /**
     * @param candidates 서버가 고른 후보 템플릿(이 안에서만 고르도록 유도)
     * @return 매칭 결과(templateId 는 후보 중 하나여야 함; 서버가 다시 검증)
     */
    RoutineMatch match(String title, String description, List<RoutineCandidate> candidates);
}