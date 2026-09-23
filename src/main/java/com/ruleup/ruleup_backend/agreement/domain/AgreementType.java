package com.ruleup.ruleup_backend.agreement.domain;

/**
 * 동의 항목 7종 — 온보딩 테크 스펙 5-7.
 *
 * <p>두 종류를 같은 구조로 저장하고 이 타입으로만 갈라낸다.
 * <ul>
 *   <li><b>약관 5종</b> — 필수 3종({@code TOS}·{@code PRIVACY}·{@code LOCATION})은 가입 트랜잭션에서
 *       받고 철회할 수 없다. 선택 2종({@code MARKETING}·{@code EVENT})은 알림 설정 기본값이 된다.</li>
 *   <li><b>법정 개별 동의 2종</b> — {@code LOCATION_INFO}·{@code HEALTH_INFO}. 위치기반 서비스 약관
 *       동의만으로 개인위치정보 동의를 대신할 수 없어 별도로 받는다. 가입 필수가 아니라
 *       <b>해당 인증 수단 최초 사용 시점</b>에 받으며, 없으면 403 {@code AGREEMENT_REQUIRED}다.</li>
 * </ul>
 *
 * <p>구 {@code NIGHT_PUSH}는 2026-08-28 폐지됐다 — 야간은 동의 여부와 무관하게 알림 분류별로
 * 일괄 처리한다(알림 정책 §5).
 */
public enum AgreementType {

    TOS(true, false),
    PRIVACY(true, false),
    LOCATION(true, false),
    MARKETING(false, false),
    EVENT(false, false),

    /** 개인위치정보 수집·이용 — 위치 인증 수단의 법적 근거. */
    LOCATION_INFO(false, true),
    /** 건강정보 수집·이용(걸음·거리·수면) — 건강 인증 수단의 법적 근거. */
    HEALTH_INFO(false, true);

    /** 가입 시 필수 여부. 개별 동의는 가입 필수가 아니므로 false 다. */
    private final boolean signupRequired;
    private final boolean individualConsent;

    AgreementType(boolean signupRequired, boolean individualConsent) {
        this.signupRequired = signupRequired;
        this.individualConsent = individualConsent;
    }

    /** 가입 필수 3종인지. 철회 금지 대상과 재동의 판정 대상이 모두 이 집합이다. */
    public boolean isRequired() {
        return signupRequired;
    }

    /** 법정 개별 동의 2종인지 — 인증 수단 게이트가 보는 값. */
    public boolean isIndividualConsent() {
        return individualConsent;
    }

    /** 가입 트랜잭션에서 기록하는 약관 5종인지. */
    public boolean isTerms() {
        return !individualConsent;
    }
}
