package com.ruleup.ruleup_backend.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link PushSender}의 기본(플레이스홀더) 구현 — 실제 전송 대신 로그만 남긴다.
 *
 * <p>아직 FCM 파이프라인(푸시 토큰 저장·Firebase Admin·자격증명)이 없으므로, 트리거·발송 흐름을
 * 끝까지 돌려볼 수 있도록 전송 자리를 이 로깅 어댑터로 채운다. Firebase Admin 자격증명이 준비되면
 * {@code FcmPushSender}({@code PushSender} 구현)를 추가하면 이 빈은 자동으로 물러난다
 * ({@link ConditionalOnMissingBean}).
 */
@Component
@ConditionalOnMissingBean(PushSender.class)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void sendSilent(UUID userId, SilentPush push) {
        // 실제 어댑터가 붙기 전까지는 "무엇을 보낼지"만 관측 가능하게 남긴다(개인정보 최소화 — 토큰/문구 없음).
        log.info("[push:stub] 무음 푸시 (미전송 — FCM 어댑터 없음) userId={} type={} data={}",
                userId, push.type(), push.data());
    }
}
