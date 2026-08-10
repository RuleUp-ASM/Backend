package com.ruleup.ruleup_backend.challenge.dto;

/**
 * 챌린지 탈퇴 응답 — 탈퇴 API 명세 200 OK.
 *
 * <p>구 명세의 "탈퇴 시 재참여 영구 불가"·"방장은 탈퇴 불가"는 둘 다 폐기됐다.
 *
 * @param scoreDelta        감점(0 = 면제·면책 적용). ⚠️ 수치 미확정 — 실제 점수 반영은 티어 모듈 소관이고
 *                          본 API 는 계약값 반환 + 트리거 로깅까지만 한다(테크스펙 Non-Goals)
 * @param exemptReason      {@code LONG_SUCCESS}(1년 이상 성공) / {@code SUCCESSION_GRACE}(승계 3일 면책) / null
 * @param botOwnerActivated 내가 방장이었고 봇방장 체제로 전환됐으면 true
 */
public record LeaveResponse(
        boolean left,
        int scoreDelta,
        String exemptReason,
        String rejoinAvailableAt,
        boolean botOwnerActivated
) {
    public static final String EXEMPT_LONG_SUCCESS = "LONG_SUCCESS";
    public static final String EXEMPT_SUCCESSION_GRACE = "SUCCESSION_GRACE";
}
