package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 3.2 생성 요청 — 추천을 수정·확정한 최종값.
 *  - 인증은 루틴에서: templateId(추천에서 받은 루틴, 매칭 실패면 null) + selectedMethod(AUTO/MANUAL)
 *    + params(이 챌린지의 목표값) + grantedPermissions(AUTO 권한 재확인용).
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
        List<String> grantedPermissions,
        PenaltyConfig penalty,
        RewardConfig reward,
        String anonymity
) {
    public Map<String, Object> paramsOrEmpty() {
        return (params != null) ? params : Map.of();
    }

    public List<String> grantedPermissionsOrEmpty() {
        return (grantedPermissions != null) ? grantedPermissions : List.of();
    }
}