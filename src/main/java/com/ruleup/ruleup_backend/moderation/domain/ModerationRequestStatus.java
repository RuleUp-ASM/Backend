package com.ruleup.ruleup_backend.moderation.domain;

/** 심사 요청 상태 (moderation_requests.status). 완료(APPROVED/REJECTED) 후에도 이력으로 보존. */
public enum ModerationRequestStatus {
    PENDING, APPROVED, REJECTED
}
