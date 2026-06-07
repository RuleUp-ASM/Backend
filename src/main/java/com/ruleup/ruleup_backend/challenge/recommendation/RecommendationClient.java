package com.ruleup.ruleup_backend.challenge.recommendation;

/**
 * 챌린지 기본값 추천을 받아오는 LLM 클라이언트 (스펙 3.1).
 *
 * 구현체를 빈으로 1개만 등록해서 갈아끼운다:
 *  - 현재: {@link SolarRecommendationClient} (@Component 활성)
 *  - 나중: {@link GeminiRecommendationClient} (지금은 @Component 주석 처리됨)
 * 전환 시 두 구현체의 @Component 주석만 서로 바꾸면 됨(서비스 코드 변경 불필요).
 *
 * 반환 타입 {@link GeminiSuggestion}은 "LLM이 준 추천 초안"의 공통 형태로,
 * 이름과 무관하게 Solar/Gemini 모두 동일 구조로 채운다. (검증·보정은 서비스 책임)
 */
public interface RecommendationClient {

    /** title(+description)로 추천 초안을 받아온다. 실패 시 BusinessException(AI_RECOMMENDATION_FAILED). */
    GeminiSuggestion recommend(String title, String description);
}