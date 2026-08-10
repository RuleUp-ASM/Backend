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
        boolean blocked,
        boolean failed
) {
    /**
     * LLM 타임아웃·공급자 오류·파싱 실패. Step1·2 안전성 판정을 통과하지 못했으므로
     * <b>이 응답으로는 AI 초안을 만들지도 저장하지도 않는다</b> — 호출부가 FALLBACK 을 반환한다.
     * 사용자 원문을 AI 초안으로 저장하면 심사 면제(EXEMPT) 경로로 무심사 노출되기 때문이다(테크스펙 4-3 P0).
     */
    public static ChallengeDraftSuggestion empty() {
        return new ChallengeDraftSuggestion(RoutineMatch.none(), ChallengeSettings.empty(), false, true);
    }

    /**
     * Step1(입력 적합성)·Step2(콘텐츠 검수) 차단. 초안을 만들지 않고 클라를 최초 생성 화면으로 되돌린다(fallback:true).
     * empty()(=LLM 장애)와 사유만 다를 뿐 응답 형태는 같다.
     */
    public static ChallengeDraftSuggestion block() {
        return new ChallengeDraftSuggestion(RoutineMatch.none(), ChallengeSettings.empty(), true, false);
    }

    /** 초안을 만들 수 없는 상태(차단 또는 장애) — 어느 쪽이든 FALLBACK 이다. */
    public boolean unusable() { return blocked || failed; }

    public RoutineMatch matchOrNone() {
        return (match != null) ? match : RoutineMatch.none();
    }

    public ChallengeSettings settingsOrEmpty() {
        return (settings != null) ? settings : ChallengeSettings.empty();
    }
}
