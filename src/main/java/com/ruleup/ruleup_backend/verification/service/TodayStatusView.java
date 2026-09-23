package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.FailExpectation;
import com.ruleup.ruleup_backend.verification.domain.Polarity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 저장 상태(VerificationStatus) + 시각 → 사용자에게 보여줄 인증 상태.
 * "오늘 인증 결과 조회"의 {@code status}와 sync 응답의 {@code updatedChallenges[].todayStatus}가 같은 값을 쓰므로
 * 매핑을 한 곳에 둔다.
 *
 * <p>상태값은 <b>진행중 · 실패 예정 · 완료 · 실패 4종</b>이다(공통 5-1, 백엔드 정합화 §4).
 * 앞의 둘은 저장하지 않고 여기서 계산한다.
 * <ul>
 *   <li>{@code IN_PROGRESS}   — 아직 채울 기회가 있고 위반도 확인되지 않음</li>
 *   <li>{@code FAIL_EXPECTED} — 이대로 가면 실패. <b>최종 실패가 아니다</b> — 늦게 도착한 신호로 뒤집힐 수 있고,
 *       유저는 이 상태에서 이의를 신청한다</li>
 *   <li>{@code DONE/FAILED}   — 확정된 결과</li>
 *   <li>{@code NOT_TARGET}    — 그 날 인증 대상이 아님</li>
 * </ul>
 *
 * <h4>구 {@code CHECKING} 은 폐기됐다</h4>
 * 확정 시각이 지나 배치가 도는 짧은 구간을 따로 보여주던 값이다. 스펙이 <b>「확정 시각에 실패
 * 예정에서 바로 완료 또는 실패로 간다」</b>로 정리하면서 응답 어휘에서 뺐다 — 유저에게 「검사중」은
 * 아무 정보가 아니고, 그 구간에도 <b>이의는 여전히 열려 있어야 한다</b>. 검사중으로 덮으면 실패
 * 예정이 보이지 않아 이의 진입점이 화면에서 사라진다.
 */
public final class TodayStatusView {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String FAIL_EXPECTED = "FAIL_EXPECTED";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String NOT_TARGET = "NOT_TARGET";

    private TodayStatusView() {}

    /**
     * @param status        저장된 판정 상태(행이 없으면 null → 진행중)
     * @param targetDate    귀속일(KST)
     * @param failureReason 확인된 위반·미달 사유(없으면 null)
     * @param polarity      목표 달성형 / 규칙 지키기형 — 귀속일이 끝난 뒤 실패 예정 여부가 갈린다
     * @param now           판정 시점
     */
    public static String of(VerificationStatus status, LocalDate targetDate, String failureReason,
                            Polarity polarity, Instant now) {
        if (status == null) return IN_PROGRESS;
        return switch (status) {
            case SUCCESS -> DONE;
            case FAILED -> FAILED;
            case NOT_TARGET, NOT_REQUIRED -> NOT_TARGET;
            // 확정 시각이 지나 배치가 도는 동안에도 같은 계산을 쓴다 — 그 구간에만 다른 값을
            // 보여주면 실패 예정이 가려져 이의 진입점이 사라진다.
            case PENDING -> FailExpectation.isExpected(status, targetDate, failureReason, polarity, now)
                    ? FAIL_EXPECTED : IN_PROGRESS;
        };
    }
}
