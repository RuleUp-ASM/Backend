package com.ruleup.ruleup_backend.common.verification;

/**
 * 인증 판정 상태 (인증 스펙 §4). VerificationDaily·VerificationMethodResult,
 * 그리고 ChallengeMember.todayStatus(비정규화)가 공유.
 *  - PENDING            : 아직 미확정(신호 대기/평가 전).
 *  - SUCCESS            : 충족.
 *  - FAILED_PROVISIONAL : 잠정 실패(그룹). 이의 제기 창(3일) 동안 유지, 온도 미반영(§8.7).
 *  - FAILED             : 미충족 확정(lock, 온도 반영). 솔로는 잠정 단계 없이 즉시 이 값.
 *  - NOT_TARGET         : 그 날 대상 아님(요일/빈도 외).
 *  - NOT_REQUIRED       : 인증 불필요(설정상).
 * DB ENUM과 1:1.
 *
 * <p>challenge·verification 두 도메인이 공유하는 값 타입이라 공유 커널(common.verification)에 둔다
 * (challenge → verification 역방향 의존을 끊기 위함).
 */
public enum VerificationStatus {
    PENDING, SUCCESS, FAILED_PROVISIONAL, FAILED, NOT_TARGET, NOT_REQUIRED;

    /** 잠정 실패(그룹, 이의 제기 창 열림). */
    public boolean isProvisionalFailure() { return this == FAILED_PROVISIONAL; }
}
