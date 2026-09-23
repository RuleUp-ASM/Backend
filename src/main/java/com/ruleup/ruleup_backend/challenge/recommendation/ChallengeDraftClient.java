package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.routine.match.RoutineCandidate;

import java.util.List;

/**
 * 설명 한 줄을 보고 챌린지 초안(제목 생성 + 설명 교정 + 카테고리 분류 + 루틴 매칭 + 기본 설정)을
 * LLM 한 번 호출로 제안한다(경로 B, 5-Step 중 Step 1~4).
 * 실패(타임아웃/파싱오류 등)는 {@link ChallengeDraftSuggestion#empty()} → 서버가 전부 폴백한다.
 */
public interface ChallengeDraftClient {

    /**
     * @param candidates 서버가 고른 후보 템플릿(이 안에서만 매칭하도록 유도)
     */
    ChallengeDraftSuggestion suggest(String description, List<RoutineCandidate> candidates);
}
