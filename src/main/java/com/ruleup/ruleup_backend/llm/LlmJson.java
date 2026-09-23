package com.ruleup.ruleup_backend.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 응답(JSON 문자열) 처리 공용 유틸 — provider 무관. {@link LlmClient} 기본 메서드가 위임한다.
 */
final class LlmJson {

    private static final Logger log = LoggerFactory.getLogger(LlmJson.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LlmJson() {}

    /** 응답 텍스트에서 첫 '{' ~ 마지막 '}' 구간만 추출(코드펜스/설명 대비). */
    static String extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /** JSON 텍스트 → 타입. 실패 시 null(출력 형식 실패는 쿼터 계열과 구분해 집계). */
    static <T> T parseJson(String content, Class<T> type) {
        try {
            return MAPPER.readValue(extractJson(content), type);
        } catch (Exception e) {
            log.warn("llm_fail class=FORMAT msg={}", e.getMessage());
            return null;
        }
    }
}
