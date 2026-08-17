package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 초대 링크 조회 응답(초대 수락 응답은 일반 가입과 같은 {@link JoinResponse}). */
public final class InvitationDtos {
    private InvitationDtos() {}

    @Schema(name = "InvitationPreviewResponse", description = "초대 링크로 들어온 사람에게 보여줄 방 정보 + 수락 가능 여부")
    public record PreviewResponse(

            @Schema(description = "초대장 식별자", example = "0192aaaa-1111-7000-bbbb-222233334444")
            String invitationId,

            @Schema(description = "초대받은 방의 요약")
            Challenge challenge,

            @Schema(description = "초대한 방장의 닉네임", example = "김지수")
            String inviterNickname,

            @Schema(description = "지금 수락할 수 있는지. false 면 blockReason 에 이유가 실린다.", example = "true")
            boolean joinable,

            @Schema(description = "수락이 막힌 이유. 수락 API 의 error.reason 과 같은 enum 이다. 가능하면 null.",
                    example = "TIER_GATE",
                    allowableValues = {"ALREADY_JOINED", "CHALLENGE_COMPLETED", "REJOIN_COOLDOWN",
                            "FREE_LIMIT", "FULL", "TIER_GATE"})
            String blockReason,

            @Schema(description = "링크 만료 시각(발급 후 7일)", example = "2026-08-21T10:00:00Z")
            String expiresAt) {

        @Schema(name = "InvitationChallengeSummary", description = "초대받은 방의 요약 — 수락 전 화면에 필요한 만큼만")
        public record Challenge(
                @Schema(example = "0192f3c1-7a2b-7c9d-8e1f-2a3b4c5d6e7f") String challengeId,
                @Schema(example = "새벽 러닝 크루") String title,
                @Schema(description = "대표 이미지. 없으면 null.") String imageUrl,
                @Schema(example = "EXERCISE") String category,
                @Schema(description = "현재 참여 인원", example = "4") int participantCount,
                @Schema(description = "정원. 제한이 없으면 null.", example = "10") Integer capacity,
                @Schema(description = "최소 표시 티어. 제한이 없으면 null.", example = "SILVER") String minTier,
                @Schema(example = "2026-08-17") String startDate,
                @Schema(example = "2026-08-31") String endDate) {}
    }
}
