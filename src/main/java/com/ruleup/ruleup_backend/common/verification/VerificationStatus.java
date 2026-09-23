package com.ruleup.ruleup_backend.common.verification;

/**
 * 인증 판정의 <b>저장</b> 상태 (인증 정책 §2.1). VerificationDaily·VerificationMethodResult,
 * 그리고 ChallengeMember.todayStatus(비정규화)가 공유한다.
 *  - PENDING      : 아직 확정되지 않음. 실패 조건이 이미 확인된 "실패 예정"도 여기에 머문다.
 *  - SUCCESS      : 완료 확정. 목표 달성형은 조건 충족 즉시.
 *  - FAILED       : 실패 확정. 귀속일 다음 날 00:00 KST 확정 배치에서만 만들어진다.
 *  - NOT_TARGET   : 그 날 대상 아님(요일/빈도 외).
 *  - NOT_REQUIRED : 인증 불필요(설정상).
 *
 * <p><b>진행중·실패 예정·검사중은 저장하지 않는다</b> — 현재 시각과 신호로 계산해 응답에만 싣는다
 * (표시값 매핑은 TodayStatusView). 확정되지 않은 상태를 저장하면 상태 전이와 이의 정정이
 * 곱절로 복잡해지고, 같은 신호에 대해 저장값과 계산값이 어긋날 수 있다.
 *
 * <p>구 정책의 FAILED_PROVISIONAL(잠정 실패 → 방장 승인)은 폐기됐다. 실패는 한 번에 확정되고
 * 구제는 이의제기 한 경로뿐이다.
 *
 * <p>challenge·verification 두 도메인이 공유하는 값 타입이라 공유 커널(common.verification)에 둔다
 * (challenge → verification 역방향 의존을 끊기 위함).
 */
public enum VerificationStatus {
    PENDING, SUCCESS, FAILED, NOT_TARGET, NOT_REQUIRED;

    /** 더 이상 자동으로 바뀌지 않는 종결 상태인지. 확정 이후 도착한 신호는 이 값을 건드리지 않는다. */
    public boolean isTerminal() { return this == SUCCESS || this == FAILED; }
}
