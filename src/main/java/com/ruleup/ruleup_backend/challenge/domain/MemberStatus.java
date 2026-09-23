package com.ruleup.ruleup_backend.challenge.domain;

/**
 * 멤버 상태.
 *  - PENDING : 그룹 참여 신청 후 운영자 승인 대기.
 *  - ACTIVE  : 참여 확정(통계 participant_count 집계 대상).
 *  - LEFT    : 본인 탈퇴.
 *  - REMOVED : 운영자 거절/강퇴.
 * DB ENUM('PENDING','ACTIVE','LEFT','REMOVED')와 1:1.
 */
public enum MemberStatus {
    PENDING, ACTIVE, LEFT, REMOVED
}