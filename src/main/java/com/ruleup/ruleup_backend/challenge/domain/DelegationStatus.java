package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 방장 위임 요청 상태 (생성 및 라이프사이클 스펙 §7-2).
 *  - PENDING  : 요청 생성. 7일 후 자동 만료(배치가 EXPIRED 로 전환).
 *  - ACCEPTED : 대상(MANAGER)이 수락 → 트랜잭션으로 role swap 완료.
 *  - REJECTED : 대상이 거절.
 *  - CANCELED : 요청자(OWNER)가 취소.
 *  - EXPIRED  : 7일 경과로 만료.
 * DB ENUM('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED')와 1:1.
 */
public enum DelegationStatus {
    PENDING, ACCEPTED, REJECTED, CANCELED, EXPIRED;

    public boolean isPending() { return this == PENDING; }
}
