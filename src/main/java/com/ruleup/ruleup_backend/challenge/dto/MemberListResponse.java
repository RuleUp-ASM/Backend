package com.ruleup.ruleup_backend.challenge.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** GET /challenges/{id}/members API 문서 계약. */
@Schema(description = "현재 멤버 목록 — 탈퇴·강퇴된 사람은 들어 있지 않다")
public record MemberListResponse(

        @Schema(description = "챌린지 id") String challengeId,

        @Schema(description = "현재 참여 인원", example = "14") int participantCount,

        @Schema(description = "정원. 제한이 없으면 null.", example = "50") Integer capacity,

        @Schema(description = "방장 유형. BOT 이면 목록에 OWNER 가 없다.",
                example = "USER", allowableValues = {"USER", "BOT"})
        String ownerType,

        List<Member> members) {

    @Schema(name = "ChallengeMemberItem", description = "멤버 한 명")
    public record Member(
            String userId,

            @Schema(description = "표시 닉네임. 차단한 사람이면 임시 닉네임, 익명 챌린지면 마스킹된 값.", example = "김지수")
            String nickname,

            @Schema(description = "프로필 이미지. 차단·익명이거나 승인 사진이 없으면 null.")
            String profileImageUrl,

            @Schema(description = "방 안 역할. MANAGER 는 폐기돼 두 값뿐이다.",
                    example = "MEMBER", allowableValues = {"OWNER", "MEMBER"})
            String role,

            @Schema(description = "표시 티어", example = "SILVER") String displayTier,

            @Schema(description = "이 방에 들어온 시각", example = "2026-08-10T09:00:00Z") String joinedAt,

            @Schema(description = "내가 차단한 사람인지. true 여도 목록에서 빠지지는 않는다.", example = "false")
            boolean blocked) {}
}
