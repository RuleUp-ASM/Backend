package com.ruleup.ruleup_backend.verification.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 앵커·측정 대상 앱 변경의 <b>월 1회</b> 한도.
 *
 * <p>"저장 1회"가 단위라서 항목 하나만 고쳐도 그 달 횟수를 소진한다. 리셋은 매월 1일 00:00 KST.
 * 첫 설정(POST /setup)은 소진하지 않으므로, 판정 기준은 마지막 저장 시각이 아니라
 * 마지막 <i>변경</i>(PUT) 시각이다.
 */
public final class MonthlyChangeLimit {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private MonthlyChangeLimit() {}

    /** 이번 달에 아직 변경 권한이 남아 있는지. 변경 이력이 없거나 지난 달 이전이면 가능. */
    public static boolean available(Instant lastChangedAt, Instant now) {
        if (lastChangedAt == null) return true;
        return !YearMonth.from(ZonedDateTime.ofInstant(lastChangedAt, KST))
                .equals(YearMonth.from(ZonedDateTime.ofInstant(now, KST)));
    }

    /** 다음 변경 가능 시각 = 다음 달 1일 00:00 KST(ISO-8601). */
    public static String nextChangeAvailableAt(Instant now) {
        return YearMonth.from(ZonedDateTime.ofInstant(now, KST))
                .plusMonths(1).atDay(1).atStartOfDay(KST).format(ISO_OFFSET);
    }

    /** 조회 응답용: 권한이 남아 있으면 null, 소진했으면 다음 달 1일 00:00 KST. */
    public static String nextChangeAvailableAtOrNull(Instant lastChangedAt, Instant now) {
        return available(lastChangedAt, now) ? null : nextChangeAvailableAt(now);
    }
}
