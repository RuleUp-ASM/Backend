package com.ruleup.ruleup_backend.verification.domain;

import com.ruleup.ruleup_backend.common.verification.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

/**
 * "이대로 가면 실패인가" 판정 (인증 정책 §2.1 실패 예정).
 *
 * <p>저장 상태가 아니라 계산 상태다. 두 경우에 실패 예정이 된다.
 * <ul>
 *   <li><b>위반이 확인됐다</b> — 장소 피하기 진입, 앱 최대 사용 초과처럼 되돌릴 수 없는 사유가 이미 잡혔다.
 *       귀속일 중이라도 실패 예정이다.</li>
 *   <li><b>귀속일이 끝났는데 목표 달성형이 아직 미달이다</b> — 더 채울 기회가 없으니 이대로면 실패다.
 *       규칙 지키기형은 반대다. 귀속일이 끝나도록 위반이 없었으면 오히려 성공 쪽이라 실패 예정이 아니다.</li>
 * </ul>
 *
 * <p>이 구분이 필요한 이유는 <b>이의 신청 자격</b> 때문이다. 확정은 D+2 00:00 이고 이의 기한도 같은 시각이라,
 * 유저는 확정 전 유예 하루 동안 "이대로면 실패"를 보고 이의를 내야 한다. 위반이 잡히는 규칙 지키기형만
 * 실패 예정으로 치면 정작 실패의 대다수인 목표 미달(안 갔다·걸음 부족)이 이의를 낼 수 없게 된다.
 */
public final class FailExpectation {

    private FailExpectation() {}

    /**
     * @param status        저장된 판정 상태
     * @param targetDate    귀속일(KST)
     * @param failureReason 확인된 위반·미달 사유(없으면 null)
     * @param polarity      목표 달성형 / 규칙 지키기형
     * @param now           판정 시점
     */
    public static boolean isExpected(VerificationStatus status, LocalDate targetDate,
                                     String failureReason, Polarity polarity, Instant now) {
        if (status == null || status.isTerminal()) return false;   // 확정된 결과는 예정이 아니다
        if (status != VerificationStatus.PENDING) return false;    // 대상 아님·인증 불필요
        if (failureReason != null) return true;                    // 되돌릴 수 없는 위반이 이미 잡혔다
        if (targetDate == null) return false;
        return polarity != Polarity.CONSTRAINT
                && VerificationDeadlines.targetDateEnded(targetDate, now);
    }
}
