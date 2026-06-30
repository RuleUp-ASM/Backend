package com.ruleup.ruleup_backend.watcher.domain;

/**
 * 감시자 통지 큐 상태.
 *  - PENDING : 적재됨(발송 대기, scheduledAt 도래 시 발송).
 *  - SENT    : 발송 완료.
 *  - SKIPPED : 발송 시점에 감시자가 ACTIVE가 아니라 발송 중단(§5.9 — 수신거부/해제).
 */
public enum WatcherNotificationStatus { PENDING, SENT, SKIPPED }
