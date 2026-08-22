package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;

import java.time.Instant;

/**
 * 내부 판정 상태를 클라 계약으로 옮기는 단일 지점.
 * "오늘 인증 결과 조회"의 {@code status}와 sync 응답의 {@code updatedChallenges[].todayStatus}가 같은 enum을 쓰므로
 * 매핑을 한 곳에 둔다.
 *
 * <p>잠정 실패도 유저에겐 FAILED 다 — 구제 경로는 today 응답의 {@code appeal}로 따로 안내한다.
 */
public final class TodayStatusView {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String CHECKING = "CHECKING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String NOT_TARGET = "NOT_TARGET";

    private TodayStatusView() {}

    /**
     * @param status         내부 판정 상태
     * @param windowClosesAt 인증 창 닫힘 시각(없으면 하루 종일) — 창이 닫힌 뒤의 미확정은 "판정 중"
     * @param now            판정 시점
     */
    public static String of(VerificationStatus status, Instant windowClosesAt, Instant now) {
        if (status == null) return IN_PROGRESS;
        return switch (status) {
            case SUCCESS -> DONE;
            case FAILED, FAILED_PROVISIONAL -> FAILED;
            case NOT_TARGET, NOT_REQUIRED -> NOT_TARGET;
            case PENDING -> (windowClosesAt != null && now.isAfter(windowClosesAt)) ? CHECKING : IN_PROGRESS;
        };
    }
}
