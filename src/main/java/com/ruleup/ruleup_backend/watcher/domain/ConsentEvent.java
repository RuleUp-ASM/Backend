package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 동의·철회 이력의 사건 종류.
 *
 * <p>입증 책임이 사업자에게 있으므로 <b>동의와 철회에 해당하는 모든 시각</b>을 남긴다.
 * 페이지2에서 채널이나 동의 범위가 세분화되면 여기에 값을 추가하는 방식으로 확장한다.
 */
public enum ConsentEvent {
    /** 인앱 수락 — 동의 성립. */
    ACCEPTED,
    /** 수신 토글 OFF — 관계는 살아 있고 통지만 닫힌다. */
    TOGGLE_OFF,
    /** 감시자가 피감시자를 차단해 통지가 나가지 않게 된 시각. */
    BLOCKED
}
