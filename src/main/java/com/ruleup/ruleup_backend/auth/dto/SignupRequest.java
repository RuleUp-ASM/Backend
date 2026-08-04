package com.ruleup.ruleup_backend.auth.dto;

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
public record SignupRequest(
        String signupToken,
        String installationId,
        String nickname,
        List<String> interestCategories,
        String birthDate,
        String gender,
        Agreements agreements,
        String deviceId,
        DeviceInfoRequest deviceInfo,
        String inviteCode) {   // 친구 초대 코드(선택). 유효하면 초대 기록 연동(마이프로필 §6.4).

    /** 약관 항목별 { agreed, version }. version 미전송 시 서버 기본값으로 저장. */
    public record AgreementItem(Boolean agreed, String version) {

        public boolean isAgreed() {
            return Boolean.TRUE.equals(agreed);
        }
    }

    /** 약관 6종 — 필수 3(이용약관·개인정보·위치기반) + 선택 3(마케팅·이벤트·야간 알림). */
    public record Agreements(
            AgreementItem termsOfService,
            AgreementItem privacyPolicy,
            AgreementItem locationService,
            AgreementItem marketing,
            AgreementItem event,
            AgreementItem nightPush) {

        /** 필수 3종이 모두 명시적으로 동의(agreed=true)됐는지. */
        public boolean requiredAllAgreed() {
            return termsOfService != null && termsOfService.isAgreed()
                    && privacyPolicy != null && privacyPolicy.isAgreed()
                    && locationService != null && locationService.isAgreed();
        }
    }
}
