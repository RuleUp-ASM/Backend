package com.ruleup.ruleup_backend.challenge.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * LLM 이 제목/설명을 보고 제안하는 챌린지 기본 설정(날것). 값 유효성은 서버가 sanitize 한다.
 * (인증·목표값은 루틴 매칭이 담당. 여기는 참여방식·일정·패널티·보상·익명만.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeSettings(
        String participationType,    // SOLO / GROUP
        List<String> repeatDays,     // ["MON", ...]
        Integer durationDays,
        BigDecimal mannerDeduction,  // 패널티 매너 차감
        BigDecimal mannerGain,       // 보상 매너 가산
        String anonymity             // REAL / ANONYMOUS
) {
    /** LLM 실패/무응답 시. 서버가 전부 기본값으로 폴백한다. */
    public static ChallengeSettings empty() {
        return new ChallengeSettings(null, null, null, null, null, null);
    }
}