package com.ruleup.ruleup_backend.user.domain;

/**
 * 프로필 이미지 심사 상태 (users.profile_image_status).
 *  - NONE     : 미등록 — 기본 프로필 표시 (심사 없이 통과)
 *  - PENDING  : 심사 중 — 타인에겐 직전 승인 이미지(없으면 기본 프로필)
 *  - APPROVED : 승인 — approved_profile_image_url = 제출 이미지
 *  - REJECTED : 거절 — 직전 승인 이미지 유지(없으면 기본 프로필)
 */
public enum ProfileImageStatus {
    NONE, PENDING, APPROVED, REJECTED
}
