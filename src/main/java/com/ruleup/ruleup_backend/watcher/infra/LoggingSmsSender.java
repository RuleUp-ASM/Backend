package com.ruleup.ruleup_backend.watcher.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로컬/테스트용 SMS fake(§14 — 실제 키 없이 전체 플로우 동작). 실제 발송 대신 로그만 남긴다.
 * prod에서는 실제 SmsSender 구현으로 교체한다(이 fake 제거 또는 @Primary 실제 빈 등록).
 */
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void sendOtp(String phone, String code) {
        // 운영성 본문 + 발신번호 사전등록 준수를 가정. 로컬에선 코드를 로그로 확인.
        log.info("[SMS-FAKE] to={} body=[룰업] 인증번호 {} (3분 내 입력)", PhoneNumbers.mask(phone), code);
    }
}
