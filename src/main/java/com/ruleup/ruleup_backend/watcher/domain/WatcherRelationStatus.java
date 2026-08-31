package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 감시 관계 상태 — <b>2종뿐</b>이다. 패널티 감시자 공통 5-3.
 *
 * <p>구 {@code CONSENTED}·{@code EXPIRED}·{@code REVOKED} 는 사라졌다. 해제 개념이 정책상
 * 폐지되면서 {@code REMOVED} 상태도 두지 않는다 — 관계는 루틴 종료 시 배치가
 * {@code removedAt} 을 채워 정리하고, 수신은 토글로 닫는다.
 */
public enum WatcherRelationStatus {
    /** 초대는 나갔으나 아직 수락 전. <b>발송 대상이 아니다</b> — 무동의 발송은 위법이다. */
    PENDING,
    /** 인앱 수락으로 동의가 성립한 상태. 이때부터만 통지를 보낼 수 있다. */
    ACTIVE
}
