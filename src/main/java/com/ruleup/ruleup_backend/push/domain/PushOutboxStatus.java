package com.ruleup.ruleup_backend.push.domain;

/** 고스트 푸시 큐 상태. 적재(PENDING) → 스윕이 발송(SENT) 또는 대상 없음/정책상 제외(SKIPPED). */
public enum PushOutboxStatus { PENDING, SENT, SKIPPED }
