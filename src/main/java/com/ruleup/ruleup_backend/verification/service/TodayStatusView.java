package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 저장 상태(VerificationStatus) + 시각 → 사용자에게 보여줄 인증 상태.
 * "오늘 인증 결과 조회"의 {@code status}와 sync 응답의 {@code updatedChallenges[].todayStatus}가 같은 값을 쓰므로
 * 매핑을 한 곳에 둔다.
 *
 * <p>진행중·실패 예정·검사중은 저장하지 않고 여기서 계산한다(인증 정책 §2.1).
 * <ul>
 *   <li>{@code IN_PROGRESS}  — 귀속일이 아직 안 끝났고 위반도 확인되지 않음</li>
 *   <li>{@code FAIL_EXPECTED}— 귀속일 중인데 위반·미달이 이미 확인됨. <b>최종 실패가 아니다</b></li>
 *   <li>{@code CHECKING}     — 귀속일이 끝났고 최종 재평가를 처리 중인 짧은 구간</li>
 *   <li>{@code DONE/FAILED}  — 확정된 결과</li>
 *   <li>{@code NOT_TARGET}   — 그 날 인증 대상이 아님</li>
 * </ul>
 */
public final class TodayStatusView {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String FAIL_EXPECTED = "FAIL_EXPECTED";
    public static final String CHECKING = "CHECKING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String NOT_TARGET = "NOT_TARGET";

    private TodayStatusView() {}

    /**
     * @param status        저장된 판정 상태(행이 없으면 null → 진행중)
     * @param targetDate    귀속일(KST) — 귀속일이 끝났는지로 검사중을 가른다
     * @param failureReason 위반·미달 사유(확정 전이면 "실패 예정"의 근거)
     * @param now           판정 시점
     */
    public static String of(VerificationStatus status, LocalDate targetDate, String failureReason, Instant now) {
        if (status == null) return IN_PROGRESS;
        return switch (status) {
            case SUCCESS -> DONE;
            case FAILED -> FAILED;
            case NOT_TARGET, NOT_REQUIRED -> NOT_TARGET;
            // 미확정: 귀속일이 끝났으면 최종 재평가 중(검사중)이 우선한다. 아직 귀속일이면 위반 여부로 갈린다.
            case PENDING -> {
                if (targetDate != null && VerificationDeadlines.targetDateEnded(targetDate, now)) yield CHECKING;
                yield (failureReason != null) ? FAIL_EXPECTED : IN_PROGRESS;
            }
        };
    }
}
