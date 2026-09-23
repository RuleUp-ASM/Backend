package com.ruleup.ruleup_backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 타인 프로필 응답 — 노출값은 "보는 사람" 기준으로 정해진다(검수·탈퇴·차단).
 */
@Schema(name = "PublicProfileResponse", description = """
        타인의 공개 프로필. 닉네임·사진은 승인된 값만 내려가고,
        탈퇴·차단 여부는 플래그로 알려준다(응답 자체를 막지는 않는다).""")
public record PublicProfileResponse(

        @Schema(description = "조회한 사용자 ID(UUID)", example = "0f7a3c1e-2b9d-4f6a-8c11-5d2e7b4a9c03",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String userId,

        @Schema(description = """
                타인에게 보이는 닉네임(승인된 값). 검수 중·거절 상태면 임시 닉네임이 내려간다.
                탈퇴한 사용자면 null.""",
                example = "규칙왕")
        String nickname,

        @Schema(description = """
                타인에게 보이는 프로필 사진 URL(승인된 값). 승인 전이면 null = 기본 프로필.
                탈퇴한 사용자면 null.""",
                example = "https://api.ruleup.app/files/0f7a3c1e-profile.jpg")
        String profileImageUrl,

        @Schema(description = "화면 표시용 티어. 점수는 공개하지 않는다.", example = "BRONZE")
        String displayTier,

        @Schema(description = """
                완료한 챌린지 수. 정상 완료한 방과, 참여 중이던 방이 삭제된 경우를 함께 센다.""",
                example = "7")
        long completedChallengeCount,

        @Schema(description = "탈퇴한 사용자 여부. true 면 닉네임·사진이 null 이라 \"탈퇴한 사용자\"로 표시한다.",
                example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean withdrawn,

        @Schema(description = "내가 차단한 상대인지 여부. 프로필 자체는 내려가므로 표현은 클라이언트가 정한다.",
                example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean blocked) {
}
