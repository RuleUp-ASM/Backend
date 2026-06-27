package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 3.2 생성 요청 — 추천을 수정·확정한 최종값.
 *  - 인증은 루틴에서: templateId(추천에서 받은 루틴, 매칭 실패면 null) + selectedMethod(AUTO/MANUAL)
 *    + params(이 챌린지의 목표값).
 *  - 권한은 생성 시점에 받지 않는다. 가입 후 최초 진입 셋업(§11.4 /setup)에서 받고,
 *    실제 게이트는 인증 평가 시점(멤버 READY 여부)에 걸린다.
 *  - endDate는 보내지 않는다(서버가 startDate + durationDays로 파생).
 */
public record CreateChallengeRequest(
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        BigDecimal minMannerTemperature,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        Long templateId,
        String selectedMethod,
        Map<String, Object> params,
        PenaltyConfig penalty,
        RewardConfig reward,
        String anonymity
) {
    public Map<String, Object> paramsOrEmpty() {
        return (params != null) ? params : Map.of();
    }
}