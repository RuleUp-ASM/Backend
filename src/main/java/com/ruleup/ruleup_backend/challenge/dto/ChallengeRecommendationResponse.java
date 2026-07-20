package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.routine.dto.RoutineOption;
import com.ruleup.ruleup_backend.routine.dto.RoutineParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * 추천 응답 — "전체 챌린지 초안". 사용자가 받아서 항목별로 수정한 뒤 생성으로 보낸다.
 * (수정은 클라에서, 최종값이 생성 요청에 담기므로 별도 수정 API 불필요. PATCH 는 생성 이후용.)
 *  - 루틴 매칭분(서버 진실): category, recommendedMethod, options, params, rationale
 *  - 챌린지 기본값(사용자 수정): participationType ~ reward (현재 정적 baseline)
 */
public record ChallengeRecommendationResponse(
        boolean fallback,       // true면 Step1·2 차단 — 나머지 필드는 의미 없음, 클라는 최초 생성 화면으로 복귀
        boolean matched,
        Long templateId,
        String title,
        String description,
        String category,
        String recommendedMethod,
        List<RoutineOption> options,
        List<RoutineParam> params,
        String rationale,
        String participationType,
        BigDecimal minMannerTemperature,
        BigDecimal maxMannerTemperature,   // 가입 기준 상한(= 생성자 현재 온도)
        Integer maxParticipants,           // 최대 참여 인원 초안
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        PenaltyConfig penalty,
        RewardConfig reward,
        String anonymity        // REAL / ANONYMOUS — 응답에서 누락 금지(§11.2)
) {
    /** Step1·2 차단: fallback:true, 나머지는 null/기본. 클라는 최초 생성 화면으로 복귀. */
    public static ChallengeRecommendationResponse blocked() {
        return new ChallengeRecommendationResponse(
                true, false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
