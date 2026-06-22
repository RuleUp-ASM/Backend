package com.ruleup.ruleup_backend.verification.domain;

/**
 * 인증 판정 상태 (인증 스펙 §4). VerificationDaily·VerificationMethodResult,
 * 그리고 ChallengeMember.todayStatus(비정규화)가 공유.
 *  - PENDING      : 아직 미확정(신호 대기/평가 전).
 *  - SUCCESS      : 충족.
 *  - FAILED       : 미충족 확정.
 *  - NOT_TARGET   : 그 날 대상 아님(요일/빈도 외).
 *  - NOT_REQUIRED : 인증 불필요(설정상).
 * DB ENUM과 1:1.
 */
public enum VerificationStatus {
    PENDING, SUCCESS, FAILED, NOT_TARGET, NOT_REQUIRED
}