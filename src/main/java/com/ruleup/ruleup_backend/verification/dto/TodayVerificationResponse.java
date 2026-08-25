package com.ruleup.ruleup_backend.verification.dto;

/**
 * GET /api/v1/challenges/{challengeId}/verifications/today 응답 —
 * 챌린지 상세의 "오늘 인증" 카드 + <b>판정 결과 모달</b> 데이터.
 *
 * <p>{@code unacknowledgedResult}가 있으면 클라는 성공/실패 모달을 띄우고 ack를 호출한다.
 *
 * @param date                 오늘 날짜(YYYY-MM-DD, KST)
 * @param status               IN_PROGRESS / FAIL_EXPECTED / CHECKING / DONE / FAILED / NOT_TARGET.
 *                             FAIL_EXPECTED는 위반·미달이 이미 확인됐지만 <b>아직 확정 전</b>이라
 *                             늦게 도착한 신호로 뒤집힐 수 있는 상태다. NOT_TARGET은 오늘 판정 대상이 아님
 * @param window               인증 창 표시 문구(자동=시간대, 수동="자정 마감"). 없으면 null
 * @param pendingReason        CHECKING인 이유(예: WAITING_SIGNAL). 그 외에는 null
 * @param confirmedAt          확정 시각(ISO-8601, KST). 성공은 조건 충족 즉시,
 *                             실패는 귀속일 이틀 뒤 00:00 KST. 미확정이면 null
 * @param failureReason        실패 사유. FAILED · FAIL_EXPECTED 일 때 채워진다
 * @param streak               연속 기록 변화
 * @param unacknowledgedResult 미확인 판정. 존재 시 클라는 모달을 띄우고 ack를 호출한다
 * @param appeal               이의 신청 가능 여부와 기한. FAILED · FAIL_EXPECTED 일 때
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
     * @param eligibleUntil 이의 신청 기한 — 확정 시각과 같은 귀속일 이틀 뒤 00:00 KST(ISO-8601).
     *                      확정 시각 +24시간이 아니라 자정 경계로 고정되며, 확정 전에 신청한다
     * @param eligible      지금 신청 가능한지. 기한 경과·이미 신청함 등이면 false. 횟수 한도는 없다
     */
    public record Appeal(String eligibleUntil, boolean eligible) {}
}
