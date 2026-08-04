package com.ruleup.ruleup_backend.agreement.domain;

/**
 * 약관 6종 (user_agreements.agreement_type — 회원 정책 §2).
 * 필수 3종: TOS(이용약관)·PRIVACY(개인정보)·LOCATION(위치기반) — 미동의 시 가입 불가.
 * 선택 3종: MARKETING·EVENT·NIGHT_PUSH — 알림 설정 기본값으로 승계.
 */
public enum AgreementType {
    TOS, PRIVACY, LOCATION, MARKETING, EVENT, NIGHT_PUSH;

    public boolean isRequired() {
        return this == TOS || this == PRIVACY || this == LOCATION;
    }
}
