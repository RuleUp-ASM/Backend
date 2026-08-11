package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LLM 이 설명을 보고 제안하는 초안 텍스트·설정(날것). 값 유효성은 서버가 sanitize 한다.
 * (인증·목표값은 루틴 매칭이 담당. 여기는 제목·교정 설명·카테고리·참여방식·주간 빈도.)
 *
 * 기간·정원·티어는 LLM 이 추측할 근거가 없어 서버 정적 기본값으로 뺐다
 * (출력 필드가 줄수록 파싱 실패율·토큰이 함께 준다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeSettings(
        String title,                // AI 생성 제목(30자 이내)
        String description,          // AI 교정 설명(200자 이내)
        String category,             // 12종 코드(분류 불가 시 ETC)
        String participationType,    // SOLO / GROUP
        Integer weeklyCount          // 주간 수행 횟수 1~7
) {
    /** LLM 실패/무응답 시. 서버가 전부 기본값으로 폴백한다. */
    public static ChallengeSettings empty() {
        return new ChallengeSettings(null, null, null, null, null);
    }
}
