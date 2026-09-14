package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;

/**
 * 목록 정렬 6종 (정책 §4.3 · API 명세). 한 번에 하나만 적용하고 기본은 인기순이다.
 *
 * <p>각 정렬은 <b>정렬 키 + 동점 보조 키(challengeId)</b> 로 전순서를 만든다. 보조 키가 없으면
 * 커서 페이징에서 같은 값끼리 순서가 흔들려 중복·누락이 생긴다.
 *
 * <p>{@link #COMPLETION_RATE}·{@link #SUCCESS_FAIL_RATIO} 는 표본 미달 방을 <b>목록에서 제외</b>한다
 * (정책 §4.4) — 값이 없는 방을 최하위로 붙이면 "완주율 순"이라는 약속이 깨지기 때문이다.
 */
public enum ExploreSort {

    // 2차 정렬값(secondary)은 <b>동점을 무엇으로 가르는가</b>이다. 비워 두면 그 자리를 UUID 가
    // 대신해 순서가 무작위로 보인다 — 참여자 수·비율처럼 동점이 흔한 정렬에서는 목록 대부분이
    // 그 상태가 된다. RECENT 는 1차 정렬 자체가 생성 시각이라 가를 것이 없다.
    POPULAR("s.recent_joins_24h", false, "s.last_joined_at_24h", null),
    PARTICIPANTS("c.participant_count", false, "c.created_at", null),
    // 표본 미달 방은 <b>정렬 ZSET 에 애초에 들어가지 않는다</b>(인덱서가 null 이면 넣지 않는다).
    // 조회 SQL 에서 다시 거를 필요가 없고, 통계 조인을 걷어낸 지금은 그 조건이 오류가 된다.
    COMPLETION_RATE("s.completion_rate", false, "c.created_at", null),
    SUCCESS_FAIL_RATIO("s.retention_rate", false, "c.created_at", null),
    RECENT("c.created_at", false, null, null),
    DEADLINE("c.end_date", true, "c.created_at", null);

    /** 1차 정렬 컬럼. */
    private final String primary;
    /** 오름차순인가(마감 임박만 true — 곧 끝나는 방이 위로). */
    private final boolean ascending;
    /** 2차 정렬 컬럼(없으면 null). 인기는 동점이면 더 최근에 몰린 쪽이 위다. */
    private final String secondary;
    /** 이 정렬에서만 추가로 걸리는 조건(표본 미달 제외). */
    private final String extraCondition;

    ExploreSort(String primary, boolean ascending, String secondary, String extraCondition) {
        this.primary = primary;
        this.ascending = ascending;
        this.secondary = secondary;
        this.extraCondition = extraCondition;
    }

    public String primary() { return primary; }
    public boolean ascending() { return ascending; }
    public String secondary() { return secondary; }
    public String extraCondition() { return extraCondition; }

    public String direction() { return ascending ? "ASC" : "DESC"; }

    /** ORDER BY 절 — 보조 키까지 붙여 전순서를 만든다. */
    public String orderBy() {
        String dir = direction();
        StringBuilder sb = new StringBuilder(primary).append(' ').append(dir);
        if (secondary != null) sb.append(", ").append(secondary).append(' ').append(dir);
        return sb.append(", c.id ").append(dir).toString();
    }

    /** 정의되지 않은 값은 400 — 클라이언트는 기본 정렬로 되돌아온다. */
    public static ExploreSort parse(String raw) {
        if (raw == null || raw.isBlank()) return POPULAR;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_SORT_TYPE);
        }
    }
}
