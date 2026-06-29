package com.ruleup.ruleup_backend.watcher.infra;

/**
 * SMS 발신 추상화(§9 — 발신번호 사전등록제 준수, 본문 [룰업] 프리픽스).
 * 로컬/테스트는 로깅 fake({@link LoggingSmsSender}), prod는 실제 게이트웨이 어댑터로 교체.
 * 합법성 게이트(§5.9): OTP는 감시자 본인이 웹에서 직접 입력한 번호로만 발송한다.
 */
public interface SmsSender {

    /** OTP 발송(야간 디퍼 예외 — 즉시). 본문 앞에 [룰업] 프리픽스를 붙여 보낸다. */
    void sendOtp(String phone, String code);
}
