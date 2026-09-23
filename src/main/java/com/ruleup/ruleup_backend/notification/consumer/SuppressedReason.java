package com.ruleup.ruleup_backend.notification.consumer;

/**
 * 푸시가 나가지 않은 이유 — CloudWatch 구조화 로그의 {@code suppressedReason} 값이다.
 *
 * <p>어느 값이 찍히든 <b>알림 센터에는 이미 적재돼 있다</b>. 이 목록은 푸시에만 해당한다.
 */
public enum SuppressedReason {

    /** 야간 보류 중 유저가 알림 센터에 들어와 이미 본 건. */
    ALREADY_READ,
    MASTER_OFF,
    GROUP_OFF,
    MUTED,
    /**
     * 광고성 정보 수신에 동의한 적이 없다 — <b>설정 토글과 별개</b>다.
     *
     * <p>가입 때 거부하면 설정 행이 아예 만들어지지 않고, 행이 없으면 마스터·그룹을 전부 ON 으로
     * 해석한다. 그래서 토글만 보면 미동의자에게 광고가 나간다.
     */
    MARKETING_CONSENT_OFF,
    /** 마케팅이 08~21시 밖으로 새어 나가려 한 경우 — 발화하면 발송 경로를 즉시 차단한다. */
    MARKETING_WINDOW,
    INTERVAL,
    /** 활성 기기가 없다. 푸시 권한을 거부했거나 로그아웃 상태다. */
    NO_DEVICE
}
