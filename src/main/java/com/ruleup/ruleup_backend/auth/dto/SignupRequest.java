package com.ruleup.ruleup_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * POST /api/v1/auth/signup 요청 — 회원가입 API 계약(2026-08-03).
 * 온보딩에서 받은 정보를 한 번에 제출한다. 프로필 사진은 이 API에서 받지 않는다
 * (가입 후 accessToken 으로 별도 등록).
 *
 * - birthDate: 필수, YYYY-MM-DD, 만 14세 미만 400 (서버 재검증)
 * - gender: 필수 필드 — MALE/FEMALE/NON_BINARY (UI 건너뛰기 시 클라가 NON_BINARY 전송)
 * - agreements: 약관 6종 각 { agreed, version }
 * - installationId: 동일 설치 다계정 가입 차단 판정 키 (회원 정책 §1)
 * - deviceId/deviceInfo: 단일 활성 기기 판정 + 기기 스펙 기반 추천
 */
@Schema(name = "SignupRequest", description = "회원가입 요청 — 온보딩 입력 전체를 한 번에 제출한다(원자적 처리)")
public record SignupRequest(

        @Schema(description = "소셜 로그인 응답으로 받은 1회용 가입 토큰.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJTSUdOVVAi...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String signupToken,

        @Schema(description = "앱 설치 단위 UUID. 로그인 때 보낸 값과 같아야 한다. 이미 활성 계정이 쓰는 설치면 403.",
                example = "8f14e45f-ea1d-4c4b-9b2f-1a2b3c4d5e6f",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String installationId,

        @Schema(description = """
                사용할 닉네임. 형식 검사 후 중복·잠금 검사를 거친다.
                가입 직후 상태는 PENDING(검수 대기)이며 검수 중에도 기능 제한은 없다.""",
                example = "규칙왕", requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname,

        @Schema(description = """
                관심 카테고리 코드 0~6개. 빈 배열로 건너뛸 수 있다.
                중복 값은 서버가 제거한 뒤 개수를 세므로 중복 때문에 상한에 걸리지 않는다.
                선택지는 GET /api/v1/categories 로 받는다.""",
                example = "[\"EXERCISE\",\"STUDY\"]")
        List<String> interestCategories,

        @Schema(description = "생년월일(YYYY-MM-DD). 필수이며 미래일 수 없다. 만 14세 미만은 가입 불가. 가입 후 수정 불가.",
                example = "1998-03-21", requiredMode = Schema.RequiredMode.REQUIRED)
        String birthDate,

        @Schema(description = "성별. 필수 필드이며 UI 에서 건너뛰더라도 클라이언트가 NON_BINARY 를 보낸다.",
                example = "MALE", allowableValues = {"MALE", "FEMALE", "NON_BINARY", "PREFER_NOT_TO_SAY"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String gender,

        @Schema(description = "약관 동의 6종. 필수 3종이 모두 agreed=true 여야 가입된다.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Agreements agreements,

        @Schema(description = "기기 식별자. 로그인 때 보낸 값과 같아야 한다.",
                example = "d3a1f2b4-77c9-4b1e-9f0a-2c5d8e7b6a10",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String deviceId,

        @Schema(description = "기기 스펙. 필수이며 형식 위반 시 INVALID_DEVICE_INFO.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        DeviceInfoRequest deviceInfo,

        @Schema(description = "친구 초대 코드(선택). 유효하면 초대 기록이 연동된다. 유효하지 않아도 가입은 진행된다.",
                example = "RU7K2M")
        String inviteCode) {   // 친구 초대 코드(선택). 유효하면 초대 기록 연동(마이프로필 §6.4).

    /** 약관 항목별 { agreed, version }. version 미전송 시 서버 기본값으로 저장. */
    @Schema(name = "AgreementItem", description = "약관 1건의 동의 여부와 동의한 버전")
    public record AgreementItem(

            @Schema(description = "동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            Boolean agreed,

            @Schema(description = "동의한 약관 버전. 생략하면 서버가 아는 현행 버전으로 기록한다(GET /api/v1/intro 참고).",
                    example = "1.0")
            String version) {

        public boolean isAgreed() {
            return Boolean.TRUE.equals(agreed);
        }
    }

    /** 약관 6종 — 필수 3(이용약관·개인정보·위치기반) + 선택 3(마케팅·이벤트·야간 알림). */
    @Schema(name = "Agreements", description = """
            약관 6종. 필수 3종(termsOfService·privacyPolicy·locationService)이 모두 agreed=true 여야 가입되고,
            선택 3종(marketing·event·nightPush)은 전부 거부해도 가입된다.
            미동의·항목 누락도 false 기록으로 남긴다(이력은 append-only).""")
    public record Agreements(

            @Schema(description = "이용약관 (필수)", requiredMode = Schema.RequiredMode.REQUIRED)
            AgreementItem termsOfService,

            @Schema(description = "개인정보 처리방침 (필수)", requiredMode = Schema.RequiredMode.REQUIRED)
            AgreementItem privacyPolicy,

            @Schema(description = "위치기반 서비스 이용약관 (필수)", requiredMode = Schema.RequiredMode.REQUIRED)
            AgreementItem locationService,

            @Schema(description = "마케팅 정보 수신 (선택)")
            AgreementItem marketing,

            @Schema(description = "이벤트·혜택 알림 (선택)")
            AgreementItem event,

            @Schema(description = "야간 푸시 알림 (선택)")
            AgreementItem nightPush) {

        /** 필수 3종이 모두 명시적으로 동의(agreed=true)됐는지. */
        public boolean requiredAllAgreed() {
            return termsOfService != null && termsOfService.isAgreed()
                    && privacyPolicy != null && privacyPolicy.isAgreed()
                    && locationService != null && locationService.isAgreed();
        }
    }
}
