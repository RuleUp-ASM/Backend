package com.ruleup.ruleup_backend.push;

import java.util.UUID;

/**
 * 서버 → 클라이언트 푸시 전송 추상화. 전송 채널(FCM 등)을 감춘다.
 *
 * <p>구현체는 대상 사용자의 등록된 푸시 토큰을 조회해 전송한다. 토큰이 없으면(미등록/로그아웃)
 * 조용히 no-op 한다 — 푸시 실패가 호출부(배치/트리거) 흐름을 깨면 안 된다.
 *
 * <p><b>정책 주의</b>: {@link #sendSilent}는 데이터 전용 "무음" 푸시다. 앱을 깨워 재설정을 유도하는 등
 * 정당한 용도로만 쓴다. 사용자가 알림을 거부한 것을 우회해 콘텐츠를 밀어넣는 용도로는 쓰지 않는다
 * (FCM/스토어 정책 위반). 현재 유일한 사용처는 셋업 미완료(권한 없음) 멤버를 깨우는 것.
 */
public interface PushSender {

    /**
     * 무음(데이터 전용) 푸시 발송. 화면에 알림을 띄우지 않고 앱만 깨운다.
     * 대상의 등록 토큰이 없으면 no-op.
     */
    void sendSilent(UUID userId, SilentPush push);
}
