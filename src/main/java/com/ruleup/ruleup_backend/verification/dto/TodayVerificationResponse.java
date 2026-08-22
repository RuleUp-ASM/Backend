package com.ruleup.ruleup_backend.verification.dto;

/**
 * GET /api/v1/challenges/{challengeId}/verifications/today 응답 —
 * 챌린지 상세의 "오늘 인증" 카드 + <b>판정 결과 모달</b> 데이터.
 *
 * <p>{@code unacknowledgedResult}가 있으면 클라는 성공/실패 모달을 띄우고 ack를 호출한다.
 *
 * @param date                 오늘 날짜(YYYY-MM-DD, KST)
 * @param status               IN_PROGRESS / CHECKING / DONE / FAILED / NOT_TARGET.
 *                             NOT_TARGET은 오늘 판정 대상이 아님
 * @param window               인증 창 표시 문구(자동=시간대, 수동="자정 마감"). 없으면 null
 * @param pendingReason        CHECKING인 이유(예: WAITING_SIGNAL). 그 외에는 null
 * @param confirmedAt          확정 시각(ISO-8601, KST). 성공은 조건 충족 즉시, 실패는 그날 정보가 빠짐없이
 *                             도착한 시점 또는 D+1 24:00 KST 중 이른 쪽. 미확정이면 null
 * @param failureReason        실패 사유. FAILED일 때만 채워진다
 * @param streak               연속 기록 변화
 * @param unacknowledgedResult 미확인 판정. 존재 시 클라는 모달을 띄우고 ack를 호출한다
 * @param appeal               이의제기 가능 여부와 기한. FAILED일 때만
 */
public record TodayVerificationResponse(
        String date,
        String status,
        String window,
        String pendingReason,
        String confirmedAt,
        String failureReason,
        StreakChange streak,
        UnacknowledgedResult unacknowledgedResult,
        Appeal appeal
) {
    /**
     * @param verificationId ack 호출에 쓰는 판정 ID
     * @param result         DONE / FAILED — 모달에 띄울 결과
     */
    public record UnacknowledgedResult(String verificationId, String result) {}

    /**
     * @param eligibleUntil 이의제기 기한 — 실패 확정일의 다음 날 00:00 KST(ISO-8601).
     *                      확정 시각 +24시간이 아니라 자정 경계로 고정된다
     * @param eligible      지금 신청 가능한지. 기한 경과·이미 신청함 등이면 false. 횟수 한도는 없다
     */
    public record Appeal(String eligibleUntil, boolean eligible) {}
}
