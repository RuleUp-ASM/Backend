package com.ruleup.ruleup_backend.verification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 인증 판정의 시간 경계 (인증 정책 §2). 코드 곳곳에 흩어지면 유형별로 어긋나므로 한곳에 둔다.
 *
 * <p>귀속일 D 하루가 세 구간으로 나뉜다.
 * <pre>
 *   D              D+1            D+2
 *   │── 귀속일 ───►│── 유예 ─────►│
 *                  │              │
 *             수행 종료        최종 확정 · 이의 마감
 * </pre>
 * <ul>
 *   <li><b>D+1 00:00 KST — 귀속일 종료.</b> 이때부터는 목표를 채울 기회가 없다. 아직 확정하지는 않는다.</li>
 *   <li><b>D+2 00:00 KST — 최종 확정.</b> 판정 유형과 무관하게 같은 시각이다. 유형별 cutoff 를 각자 두면
 *       "언제 확정되는지"가 사용자마다 달라져 정책·UX·운영이 모두 복잡해진다.</li>
 * </ul>
 *
 * <p>유예 하루(D+1)를 두는 이유: 신호는 늦게 도착한다. 절전·오프라인·Health Connect 수면 세션처럼
 * 귀속일이 끝난 뒤에야 올라오는 기록이 흔해서, 귀속일 종료 즉시 확정하면 실제로 수행한 사람이 실패한다.
 * 이 하루 동안 도착한 신호는 <b>발생 시각이 귀속일 조건에 맞으면 그대로 인정</b>한다.
 *
 * <p>이의 신청 기한도 같은 D+2 00:00 KST 다. 확정 시각 기준 상대 24시간이 아니라 자정 경계로 고정한다 —
 * 점수·랭킹·통계 재계산을 하루 단위로 묶어 돌리기 위함이다. 확정 <b>전에</b> 받으므로,
 * 유저는 유예 하루 동안 "이대로면 실패"를 보고 이의를 낼 수 있다.
 *
 * <p>기준 시간대는 KST 고정이다. 기기 시간대 변경·해외 체류·기기 시간 조작과 무관하게 서버가 정한다.
 */
public final class VerificationDeadlines {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 귀속일 종료 후 확정까지 두는 유예(일). 늦게 도착하는 신호를 받아 주는 구간이다. */
    private static final int GRACE_DAYS = 1;

    private VerificationDeadlines() {}

    /** 귀속일이 끝나는 시각 — D+1 00:00 KST. 이후로는 목표를 채울 기회가 없다(확정은 아직). */
    public static Instant targetDateEndsAt(LocalDate targetDate) {
        return targetDate.plusDays(1).atStartOfDay(KST).toInstant();
    }

    /** 최종 확정 시각 — D+2 00:00 KST. 이 시각 전에는 어떤 실패도 확정되지 않는다. */
    public static Instant finalizeAfter(LocalDate targetDate) {
        return targetDate.plusDays(1L + GRACE_DAYS).atStartOfDay(KST).toInstant();
    }

    /** 이의 신청 기한 — 확정 시각과 같은 D+2 00:00 KST. 확정 전에 받는다. */
    public static Instant appealClosesAt(LocalDate targetDate) {
        return finalizeAfter(targetDate);
    }

    /** 귀속일이 끝났는지 — 끝났으면 더 채울 기회가 없어 "이대로면 실패"를 보여줄 수 있다. */
    public static boolean targetDateEnded(LocalDate targetDate, Instant now) {
        return !now.isBefore(targetDateEndsAt(targetDate));
    }

    /** 확정 시각이 지났는지 — 지났으면 최종 재평가 구간(검사중)이다. */
    public static boolean finalizeDue(LocalDate targetDate, Instant now) {
        return !now.isBefore(finalizeAfter(targetDate));
    }
}
