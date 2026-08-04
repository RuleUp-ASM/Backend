package com.ruleup.ruleup_backend.user.dto;

import com.ruleup.ruleup_backend.auth.dto.UserResponse;

import java.util.Map;

/**
 * GET /api/v1/users/me 응답 (내 프로필 조회 API 계약 — 2026-08-03 신설, 오픈 이슈 #5).
 * user 블록은 로그인 응답과 동일 스키마 + 본인만 볼 수 있는 항목(생일·성별·약관 동의)을 추가.
 * agreements 키: termsOfService/privacyPolicy/locationService/marketing/event/nightPush.
 */
public record UserMeResponse(
        UserResponse user,
        String birthDate,
        String gender,
        Map<String, AgreementState> agreements) {

    /** 약관별 현재 상태 — append-only 이력의 최신 행. agreedAt = 그 행의 기록 시각. */
    public record AgreementState(boolean agreed, String version, String agreedAt) {}
}
