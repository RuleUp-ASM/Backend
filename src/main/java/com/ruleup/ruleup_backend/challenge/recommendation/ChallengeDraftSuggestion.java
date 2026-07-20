package com.ruleup.ruleup_backend.challenge.recommendation;

import com.ruleup.ruleup_backend.routine.match.RoutineMatch;

/**
 * LLM 한 번 호출로 받아오는 챌린지 초안 묶음(날것).
 *  - match    : 루틴 매칭(templateId·목표값) — 서버가 카탈로그/스키마로 재검증
 *  - settings : 참여방식·일정·패널티·보상 등 챌린지 기본값 — 서버가 sanitize
 *
 * 예전엔 루틴 매칭 / 설정 제안을 Gemini 에 따로 두 번 물어 직렬 지연이 두 배였다.
 * 둘 다 입력이 (제목, 설명)으로 같고 서로 의존이 없어 한 프롬프트로 합쳤다(왕복 1회).
 */
public record ChallengeDraftSuggestion(
        RoutineMatch match,
        ChallengeSettings settings,
        boolean blocked
) {
    /** LLM 실패/무응답 시. 매칭은 none, 설정은 empty → 서버가 전부 폴백(기본 템플릿으로 초안 제공). */
    public static ChallengeDraftSuggestion empty() {
        return new ChallengeDraftSuggestion(RoutineMatch.none(), ChallengeSettings.empty(), false);
    }

    /**
     * Step1(입력 적합성)·Step2(콘텐츠 검수) 차단. 초안을 만들지 않고 클라를 최초 생성 화면으로 되돌린다(fallback:true).
     * empty()(=LLM 장애 시 기본 템플릿 제공)와 구분된다.
     */
    public static ChallengeDraftSuggestion block() {
        return new ChallengeDraftSuggestion(RoutineMatch.none(), ChallengeSettings.empty(), true);
    }

    public RoutineMatch matchOrNone() {
        return (match != null) ? match : RoutineMatch.none();
    }

    public ChallengeSettings settingsOrEmpty() {
        return (settings != null) ? settings : ChallengeSettings.empty();
    }
}
