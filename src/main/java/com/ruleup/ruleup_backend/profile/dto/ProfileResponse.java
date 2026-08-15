package com.ruleup.ruleup_backend.profile.dto;

import com.ruleup.ruleup_backend.user.domain.NicknamePolicy;
import com.ruleup.ruleup_backend.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET/PATCH /profile/me 공통 응답. 시각은 ISO-8601 문자열.
 * nickname/profileImageUrl은 항상 "본인이 정한 값"(본인 화면이므로).
 * nicknameStatus/profileImageStatus로 타인에게 어떻게 보이는지(검수 상태)를 알려준다.
 *  - APPROVED: 타인에게도 본인 값 노출
 *  - PENDING : 검수 전/보류 → 타인에게는 임시 닉네임(tempNickname)·사진 숨김
 *  - REJECTED: 거절됨 → "바꿔주세요". 타인에게는 임시 닉네임·사진 숨김
 */
@Schema(name = "ProfileResponse", description = """
        내 프로필. nickname·profileImageUrl 은 항상 본인이 정한 값이고,
        타인에게 지금 어떻게 보이는지는 nicknameStatus·profileImageStatus 로 판단한다.""")
public record ProfileResponse(

        @Schema(description = "사용자 ID(UUID)", example = "0f7a3c1e-2b9d-4f6a-8c11-5d2e7b4a9c03")
        String id,

        @Schema(description = "본인이 정한 닉네임(검수 상태와 무관)", example = "규칙왕")
        String nickname,

        @Schema(description = "소셜 계정 이메일. 제공자가 주지 않았으면 null.", example = "ruleup@kakao.com")
        String email,

        @Schema(description = "본인이 올린 프로필 사진 URL. 없으면 null(기본 프로필).",
                example = "https://api.ruleup.app/files/0f7a3c1e-profile.jpg")
        String profileImageUrl,

        @Schema(description = """
                닉네임 검수 상태. PENDING·REJECTED 이면 타인에게는 tempNickname 이 보인다.
                REJECTED 는 변경을 유도해야 하고, 이 경우의 재변경은 30일 제한에서 빠진다.""",
                example = "APPROVED", allowableValues = {"PENDING", "APPROVED", "REJECTED", "CONFLICT"})
        String nicknameStatus,

        @Schema(description = "사진 검수 상태. APPROVED 가 아니면 타인에게는 기본 프로필이 보인다.",
                example = "PENDING", allowableValues = {"NONE", "PENDING", "APPROVED", "REJECTED"})
        String profileImageStatus,

        @Schema(description = "검수 미승인 시 타인에게 보이는 임시 닉네임", example = "성실한다람쥐")
        String tempNickname,

        @Schema(description = "마지막 닉네임 변경 시각(ISO-8601). 변경한 적 없으면 null.",
                example = "2026-07-16T04:11:07Z")
        String nicknameChangedAt,

        @Schema(description = """
                다음 닉네임 변경이 가능해지는 시각(마지막 변경 +30일).
                null 이면 지금 바로 변경할 수 있다.""",
                example = "2026-08-15T04:11:07Z")
        String nicknameChangeableAfter,

        @Schema(description = "매너 온도", example = "36.5")
        BigDecimal mannerTemperature,

        @Schema(description = "선택한 관심 카테고리 코드 목록", example = "[\"EXERCISE\",\"STUDY\"]")
        List<String> interestCategories,

        @Schema(description = "가입 시각(ISO-8601)", example = "2026-08-01T09:12:33Z")
        String createdAt) {

    public static ProfileResponse from(User user, BigDecimal temp) {
        Instant changedAt = user.getNicknameChangedAt();
        String changeableAfter = (changedAt != null)
                ? changedAt.plus(NicknamePolicy.CHANGE_INTERVAL).toString() : null;
        return new ProfileResponse(
                user.getId().toString(), user.getNickname(), user.getEmail(), user.getProfileImageUrl(),
                user.getNicknameStatus().name(), user.getProfileImageStatus().name(), user.getApprovedNickname(),
                (changedAt != null ? changedAt.toString() : null),
                changeableAfter,
                temp, user.getInterestCategories(),
                (user.getCreatedAt() != null ? user.getCreatedAt().toString() : null));
    }
}
