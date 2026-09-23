package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 극성 (인증 스펙). 방식별 판정 방향.
 *  - ACHIEVEMENT : 도달형(해야 성공). 예: 3km 달리기, 30분 독서.
 *  - CONSTRAINT  : 제약형(안 해야 성공). 예: SNS 30분 이하, 취침 전 폰 금지.
 */
public enum Polarity {
    ACHIEVEMENT, CONSTRAINT
}