package com.ruleup.ruleup_backend.profile.dto;

import com.ruleup.ruleup_backend.user.domain.ProfileImageStatus;

/**
 * POST /api/v1/users/me/profile-image 응답 — API 계약(2026-08-03).
 *  - imageUrl : 등록된 이미지 URL (본인 화면 표시용)
 *  - status   : 등록 직후에는 항상 PENDING — 심사 통과 전까지 타인에겐 기본 이미지가 보인다
 */
public record ProfileImageResponse(String imageUrl, String status) {

    public static ProfileImageResponse of(String imageUrl, ProfileImageStatus status) {
        return new ProfileImageResponse(imageUrl, status.name());
    }
}
