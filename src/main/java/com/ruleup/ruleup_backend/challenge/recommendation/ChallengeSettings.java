package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * LLM 이 제목/설명을 보고 제안하는 챌린지 기본 설정(날것). 값 유효성은 서버가 sanitize 한다.
 * (인증·목표값은 루틴 매칭이 담당. 여기는 참여방식·반복요일만.)
 *
 * 기간(durationDays)·매너 점수(deduction/gain)는 LLM 이 추측할 근거가 없어 서버 정적 기본값으로 뺐다
 * (출력 필드가 줄수록 파싱 실패율·토큰이 함께 준다). {@link ChallengeRecommendationService} 참고.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeSettings(
        String participationType,    // SOLO / GROUP
        List<String> repeatDays      // ["MON", ...]
) {
    /** LLM 실패/무응답 시. 서버가 전부 기본값으로 폴백한다. */
    public static ChallengeSettings empty() {
        return new ChallengeSettings(null, null);
    }
}
