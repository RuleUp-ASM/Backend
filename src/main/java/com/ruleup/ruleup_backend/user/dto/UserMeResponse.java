package com.ruleup.ruleup_backend.user.dto;

import com.ruleup.ruleup_backend.auth.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * GET /api/v1/users/me 응답 (내 프로필 조회 API 계약 — 2026-08-03 신설, 오픈 이슈 #5).
 * user 블록은 로그인 응답과 동일 스키마 + 본인만 볼 수 있는 항목(생일·성별·약관 동의)을 추가.
 * agreements 키: termsOfService/privacyPolicy/locationService/marketing/event/nightPush.
 */
@Schema(name = "UserMeResponse", description = """
        내 프로필. 로그인 응답의 user 블록에 본인만 볼 수 있는 항목(생일·성별·약관 동의 상태)을 더한 것이다.""")
public record UserMeResponse(

        @Schema(description = "로그인·가입 응답과 동일한 사용자 정보 블록",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UserResponse user,

        @Schema(description = "생년월일(YYYY-MM-DD). 수집하지 않았으면 null.", example = "1998-03-21")
        String birthDate,

        @Schema(description = "성별. 수집하지 않았으면 null.", example = "MALE",
                allowableValues = {"MALE", "FEMALE", "NON_BINARY", "PREFER_NOT_TO_SAY"})
        String gender,

        @Schema(description = """
                약관별 현재 동의 상태. 키는 가입 요청과 같다
                (termsOfService · privacyPolicy · locationService · marketing · event · nightPush).
                동의 이력이 한 번도 없는 약관은 키 자체가 없다.
                저장된 version 을 GET /api/v1/intro 의 현행 버전과 비교해 재동의 필요 여부를 판단한다.""")
        Map<String, AgreementState> agreements) {

    /** 약관별 현재 상태 — append-only 이력의 최신 행. agreedAt = 그 행의 기록 시각. */
    @Schema(name = "AgreementState", description = "약관 1건의 현재 상태(이력의 최신 행)")
    public record AgreementState(

            @Schema(description = "동의 여부", example = "true") boolean agreed,

            @Schema(description = "동의한 약관 버전", example = "1.0") String version,

            @Schema(description = "그 상태가 기록된 시각(ISO-8601)", example = "2026-08-01T09:12:33Z")
            String agreedAt) {}
}
