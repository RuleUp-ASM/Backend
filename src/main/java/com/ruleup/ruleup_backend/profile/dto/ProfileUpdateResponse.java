package com.ruleup.ruleup_backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 프로필 편집 결과(PATCH /users/me/profile). */
@Schema(name = "ProfileUpdateResponse", description = "반영된 값과 잠금 해제 시각.")
public record ProfileUpdateResponse(

        @Schema(description = "반영된 닉네임", example = "새벽러너") String nickname,

        @Schema(description = "닉네임 심사 상태. 변경했다면 재심사가 시작되어 PENDING 이다.",
                example = "PENDING") String nicknameStatus,

        @Schema(description = "반영된 관심 분야") List<String> interestCategories,

        @Schema(description = """
                닉네임·사진 잠금 해제 시각(마지막 저장 +1개월). 잠긴 적이 없으면 null.""",
                example = "2026-09-30T04:11:07Z") String profileLockedUntil) {}
