package com.ruleup.ruleup_backend.llm;

/**
 * LLM 호출 추상화. 제공자(Gemini · AWS Bedrock Nova 등)를 감춘다.
 *
 * <p>구현체는 {@code app.llm.provider} 설정으로 하나만 활성화된다(조건부 빈). 소비자(챌린지 초안·검수)는
 * 이 인터페이스에만 의존하므로, provider 를 env 로 바꾸면 코드 변경 없이 모델을 교체할 수 있다.
 * "여러 모델을 토큰 대비 성능으로 비교"하는 용도 — 각 provider 코드는 지우지 않고 스위치만 한다.
 *
 * <p>실패/미설정은 예외 없이 {@code null} 반환 → 호출 측이 각자 폴백(none/empty/UNAVAILABLE)한다.
 */
public interface LlmClient {

    /** 호출 가능한 상태인지(키·자격증명·모델 설정 완료). */
    boolean isConfigured();

    /** 텍스트 프롬프트 → 모델 텍스트(보통 JSON 문자열). 실패/미설정이면 null. */
    String generateText(String prompt);

    /**
     * 텍스트 프롬프트 + 응답 스키마 강제 → 모델 텍스트. 실패/미설정이면 null.
     * 스키마 형식은 provider 별로 다르며(예: Gemini OpenAPI 서브셋), 스키마를 네이티브로 강제하지
     * 못하는 provider 는 프롬프트 지시 + JSON 파싱 폴백으로 처리한다(스키마 무시).
     */
    String generateStructured(String prompt, String responseSchemaJson);

    /** 텍스트 + 이미지(멀티모달) → 모델 텍스트. 실패/미설정이면 null. */
    String generateText(String prompt, byte[] image, String mimeType);

    /** 응답 텍스트에서 JSON 객체 부분만 추출(펜스/설명이 섞여도 대비). */
    default String extractJson(String raw) {
        return LlmJson.extractJson(raw);
    }

    /** 모델 텍스트를 원하는 타입으로 파싱. 실패 시 null(llm_fail class=FORMAT 로그). */
    default <T> T parseJson(String content, Class<T> type) {
        return LlmJson.parseJson(content, type);
    }
}
