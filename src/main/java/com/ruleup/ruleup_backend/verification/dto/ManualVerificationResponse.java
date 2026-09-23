package com.ruleup.ruleup_backend.verification.dto;

/**
 * POST /api/v1/challenges/{challengeId}/verifications 응답.
 *
 * @param verificationId 인증 건 ID — 취소(DELETE /verifications/{id})에 쓴다
 * @param targetDate     귀속일(YYYY-MM-DD, KST)
 * @param status         "DONE" 고정 — 제출 즉시 확정된다
 * @param streak         연속 기록 변화
 * @param scoreNote      "MANUAL_NO_SCORE" 고정. 수동 방은 점수 미반영이지만 성공률·랭킹·통계에는 포함된다
 */
public record ManualVerificationResponse(
        String verificationId,
        String targetDate,
        String status,
        StreakChange streak,
        String scoreNote
) {
    /** 수동 방 점수 미반영 표식. */
    public static final String MANUAL_NO_SCORE = "MANUAL_NO_SCORE";
}
