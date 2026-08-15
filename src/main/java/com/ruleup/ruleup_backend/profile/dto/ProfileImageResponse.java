package com.ruleup.ruleup_backend.profile.dto;

import com.ruleup.ruleup_backend.user.domain.ProfileImageStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /api/v1/users/me/profile-image 응답 — API 계약(2026-08-03).
 *  - imageUrl : 등록된 이미지 URL (본인 화면 표시용)
 *  - status   : 등록 직후에는 항상 PENDING — 심사 통과 전까지 타인에겐 기본 이미지가 보인다
 */
@Schema(name = "ProfileImageResponse", description = "프로필 사진 등록 결과")
public record ProfileImageResponse(

        @Schema(description = "등록된 이미지 URL. 본인 화면에는 즉시 표시된다.",
                example = "https://api.ruleup.app/files/0f7a3c1e-profile.jpg",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String imageUrl,

        @Schema(description = """
                검수 상태. 등록 직후에는 항상 PENDING 이다(검수는 비동기).
                승인 전까지 타인에게는 기본 프로필이 보인다.""",
                example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
        String status) {

    public static ProfileImageResponse of(String imageUrl, ProfileImageStatus status) {
        return new ProfileImageResponse(imageUrl, status.name());
    }
}
