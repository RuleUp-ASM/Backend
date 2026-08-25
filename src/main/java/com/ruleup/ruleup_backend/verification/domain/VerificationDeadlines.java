package com.ruleup.ruleup_backend.verification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 인증 판정의 두 시간 경계 (인증 정책 §2 · 테크스펙 §5-1). 코드 곳곳에 흩어지면 유형별로 어긋나므로 한곳에 둔다.
 *
 * <ul>
 *   <li><b>최종 확정</b> — 귀속일 다음 날 00:00 KST. 위치·걸음·앱 사용·기상·수면 전부 같은 시각이다.
 *       유형별 cutoff 를 각자 두면 "언제 확정되는지"가 사용자마다 달라져 정책·UX·운영이 모두 복잡해진다.</li>
 *   <li><b>이의 신청 기한</b> — 실패 <b>확정일</b>의 다음 날 00:00 KST. 확정 시각 기준 상대 24시간이 아니라
 *       자정 경계로 고정한다 — 점수·랭킹·통계 재계산을 하루 단위로 묶어 돌리기 위함이다.
 *       정상 흐름에서는 귀속일 기준 D+2 00:00 KST 가 된다.</li>
 * </ul>
 *
 * <p>기준 시간대는 KST 고정이다. 기기 시간대 변경·해외 체류·기기 시간 조작과 무관하게 서버가 정한다.
 */
public final class VerificationDeadlines {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private VerificationDeadlines() {}

    /** 귀속일의 최종 확정 시각 — D+1 00:00 KST. 이 시각 전에는 어떤 실패도 확정되지 않는다. */
    public static Instant finalizeAfter(LocalDate targetDate) {
        return targetDate.plusDays(1).atStartOfDay(KST).toInstant();
    }

    /** 실패 확정 시각 → 이의 신청 기한. 확정일의 다음 날 00:00 KST. */
    public static Instant appealClosesAt(Instant confirmedAt) {
        return ZonedDateTime.ofInstant(confirmedAt, KST)
                .toLocalDate().plusDays(1).atStartOfDay(KST).toInstant();
    }

    /** 귀속일이 끝났는지 — 끝났으면 최종 재평가 구간(검사중)이다. */
    public static boolean targetDateEnded(LocalDate targetDate, Instant now) {
        return !now.isBefore(finalizeAfter(targetDate));
    }
}
