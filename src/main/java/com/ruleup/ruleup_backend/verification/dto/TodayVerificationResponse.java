package com.ruleup.ruleup_backend.verification.dto;

/**
 * GET /api/v1/challenges/{challengeId}/verifications/today 응답 —
 * 챌린지 상세의 "오늘 인증" 카드 + <b>판정 결과 모달</b> 데이터.
 *
 * <p>{@code unacknowledgedResult}가 있으면 클라는 성공/실패 모달을 띄우고 ack를 호출한다.
 *
 * @param verificationId       오늘 판정 행의 ID. <b>이의 신청 경로</b>
 *                             {@code POST /api/v1/verifications/{verificationId}/appeals} 에 그대로 쓴다.
 *                             행이 없으면(신호가 한 번도 없었던 진행중·비대상) null 이다.
 *                             예전에는 {@code unacknowledgedResult} 안에만 있었는데, 그 값은 <b>이미 확인한
 *                             판정에서는 사라진다</b> — 화면을 다시 열면 실패 예정이라 이의 버튼은 살아 있는데
 *                             누를 대상 ID 가 없었다
 * @param date                 오늘 날짜(YYYY-MM-DD, KST)
 * @param status               IN_PROGRESS / FAIL_EXPECTED / DONE / FAILED / NOT_TARGET —
 *                             <b>상태값 4종</b>(진행중·실패 예정·완료·실패) + 비대상이다.
 *                             FAIL_EXPECTED는 위반·미달이 이미 확인됐지만 <b>아직 확정 전</b>이라
 *                             늦게 도착한 신호로 뒤집힐 수 있는 상태이고, <b>이의를 내는 구간</b>이다.
 *                             구 {@code CHECKING}은 폐기됐다 — 확정 배치가 도는 짧은 구간에도
 *                             실패 예정이 보여야 이의 진입점이 사라지지 않는다
 * @param window               인증 창 표시 문구(자동=시간대, 수동="자정 마감"). 없으면 null
 * @param pendingReason        <b>데이터 부족 사유</b> — {@code PERMISSION_MISSING}(권한이 꺼져
 *                             측정 불가) / {@code NO_SIGNAL}(권한은 있으나 쓸 신호가 없음).
 *                             목표 미달은 여기 오지 않는다 — 그건 잴 수 있었고 못 미친 것이라
 *                             {@code failureReason} 이다. 유저가 할 일이 달라서 층을 나눈다.
 *                             <b>필드 이름은 그대로 둔다</b> — 담는 값이 바뀌었을 뿐이고,
 *                             이름을 고치면 안드로이드가 함께 바뀌어야 한다. 구 값
 *                             {@code WAITING_SIGNAL} 은 {@code CHECKING} 폐기와 함께 사라졌다
 * @param confirmedAt          확정 시각(ISO-8601, KST). 성공은 조건 충족 즉시,
 *                             실패는 귀속일 이틀 뒤 00:00 KST. 미확정이면 null
 * @param failureReason        실패 사유 코드. FAILED · FAIL_EXPECTED 일 때 채워진다
 * @param evidenceSummary      <b>판정 근거 요약</b> — 「체류 42분 / 목표 60분」처럼 사람이 읽는
 *                             한 줄이다. 개인정보보호법의 자동화된 결정 설명 요구가 사유 코드와
 *                             이 근거를 함께 요구한다(공통 5-8). 실패 예정 구간에서는 그 시점
 *                             신호로 계산하고, 확정된 실패는 판정 당시 스냅샷을 그대로 읽는다
 * @param streak               연속 기록 변화
 * @param unacknowledgedResult 미확인 판정. 존재 시 클라는 모달을 띄우고 ack를 호출한다
 * @param appeal               이의 신청 가능 여부와 기한. FAILED · FAIL_EXPECTED 일 때
 */
public record TodayVerificationResponse(
        String verificationId,
        String date,
        String status,
        String window,
        String pendingReason,
        String confirmedAt,
        String failureReason,
        String evidenceSummary,
        StreakChange streak,
        UnacknowledgedResult unacknowledgedResult,
        Appeal appeal
) {
    /**
     * @param verificationId ack 호출에 쓰는 판정 ID
     * @param result         DONE / FAILED — 모달에 띄울 결과
     */
    public record UnacknowledgedResult(String verificationId, String result) {}
    // 최상위 verificationId 와 같은 값이다. 함께 두는 이유는 구버전 앱이 이 자리를 읽기 때문이고,
    // 이 안의 값이 있다는 것은 「모달을 띄워야 한다」는 뜻이라 의미가 다르다.

    /**
     * @param eligibleUntil 이의 신청 기한 — 확정 시각과 같은 귀속일 이틀 뒤 00:00 KST(ISO-8601).
     *                      확정 시각 +24시간이 아니라 자정 경계로 고정되며, 확정 전에 신청한다
     * @param eligible      지금 신청 가능한지. 기한 경과·이미 신청함 등이면 false. 횟수 한도는 없다
     */
    public record Appeal(String eligibleUntil, boolean eligible) {}
}
