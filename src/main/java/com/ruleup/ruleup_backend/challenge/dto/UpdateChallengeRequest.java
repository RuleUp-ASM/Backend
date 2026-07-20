package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 수정 요청(§4) — 변경 필드만(전부 선택, null이면 변경 안 함).
 *  - maxParticipants: 유일하게 언제든(시작 전·진행 중) 수정 가능. 나머지는 UPCOMING + 멤버 0명에서만.
 *  - params: 목표값 조정(같은 루틴, 값만). 보내면 템플릿 스키마로 재검증.
 *  - 인증 방식(AUTO↔MANUAL)/루틴 교체는 수정 범위 제외(필요해지면 재생성).
 */
public record UpdateChallengeRequest(
        String title,
        String description,
        String imageUrl,
        String category,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        Map<String, Object> params,
        PenaltyConfig penalty,
        RewardConfig reward,
        BigDecimal minMannerTemperature,
        Integer maxParticipants
) {}