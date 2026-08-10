package com.ruleup.ruleup_backend.challenge.dto;

import com.ruleup.ruleup_backend.challenge.domain.PenaltyConfig;
import com.ruleup.ruleup_backend.challenge.domain.RewardConfig;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ChallengeDetailResponse(
        String challengeId,
        String title,
        String description,
        String imageUrl,
        String category,
        String participationType,
        String status,
        String moderationStatus,
        String fixDeadline,
        String anonymity,
        Owner owner,
        List<String> repeatDays,
        Integer durationDays,
        String startDate,
        String endDate,
        Long templateId,
        VerificationConfig verification,
        Map<String, Object> params,
        PenaltyConfig penalty,
        RewardConfig reward,
        Integer maxParticipants,
        Stats stats,
        Eligibility eligibility,
        String myRole,   // 요청자의 역할: OWNER / MANAGER / MEMBER / NONE
        String ownerType,        // USER / BOT — 봇방장 방이면 선착순 클레임 진입점을 띄운다
        Gate gate,               // 최소 티어 게이트(구 매너온도 게이트 대체)
        String joinBlockReason,  // 지금 못 들어가는 이유 미리보기 — 가입 API reason과 동일 enum
        String rejoinAvailableAt,// joinBlockReason=REJOIN_COOLDOWN 일 때만
        String joinNote          // NEXT_CYCLE(사이클 중간 입장) / IMMEDIATE
) {
    public record Owner(String nickname) {}

    /** 티어 게이팅 미리보기 — 표시 티어 기준(스펙 §5 ⑥). */
    public record Gate(String minTier, String myDisplayTier, boolean eligible) {}

    public record Stats(
            Integer participantCount,
            BigDecimal averageMannerTemperature,
            BigDecimal completionRate
    ) {}

    /** canJoin은 정원·기준온도·재참여·모더레이션·상태를 종합한 참여 가능 여부. */
    public record Eligibility(
            boolean canJoin,
            BigDecimal myMannerTemperature,
            BigDecimal minMannerTemperature,
            boolean rejoinBlocked
    ) {}
}