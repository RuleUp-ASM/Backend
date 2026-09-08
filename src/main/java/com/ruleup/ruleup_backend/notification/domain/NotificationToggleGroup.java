package com.ruleup.ruleup_backend.notification.domain;

/**
 * 토글 그룹 — 발송 판정의 입력이자 <b>감사 스냅샷</b>이다. 공통 #20(2026-09-08 종결).
 *
 * <p>설정 모델이 「마스터 1개 + 그룹 3종」으로 확정되면서 유형별 토글 안은 폐기됐다. 값은
 * {@code notifications.toggle_group} 에 적재 시점 그대로 복사되며, 푸시 payload 의
 * {@code data.toggle_group} 으로도 같은 값이 내려간다.
 *
 * <p>적재 시점에 복사하는 이유는 하나다 — 나중에 타입의 그룹 귀속이 바뀌어도
 * <b>이미 발행된 알림이 어느 토글로 걸러졌는지</b>는 그대로 남아야 한다.
 */
public enum NotificationToggleGroup {

    /** 강퇴 · 잠금 · 심사 결과 · 이의 결과 등 고지 성격. */
    ACCOUNT,

    /** 판정 결과 · 티어 · 감시자 통지 등 서비스 이용 알림. */
    CHALLENGE,

    /** 광고성 프로모션. 앱 토글이 약관의 수신 동의 상태와 연동된다. */
    MARKETING,

    /**
     * 그룹 토글이 없는 타입 — 루틴 리마인더(상시)와 운영자 공지(푸시 대상 아님).
     *
     * <p>리마인더에 그룹을 주지 않는 것은 정책이다. 설정 화면에도 토글 없이
     * 「루틴 리마인더는 항상 켜져 있어요」 안내문만 둔다. 마스터와 챌린지 음소거만 영향을 준다.
     */
    NONE;

    /** 설정 화면에 그룹 토글이 있는지. NONE 은 그룹 판정을 <b>통과</b>한다. */
    public boolean isTogglable() {
        return this != NONE;
    }
}
