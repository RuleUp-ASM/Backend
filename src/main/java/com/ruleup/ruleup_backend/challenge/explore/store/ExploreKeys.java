package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;

import java.util.UUID;

/**
 * 탐색 Redis 키 규약 (탐색 테크스펙 5-3).
 *
 * <p>접두사를 {@code explore:} 하나로 묶는 이유는 <b>재구성 단위가 곧 삭제 단위</b>이기 때문이다.
 * 파생 데이터는 언제든 원천에서 다시 만들 수 있으므로, 이상하면 이 접두사째로 날리고 워밍업을
 * 다시 돌리는 것이 가장 빠른 복구다. 원천(MySQL)과 섞이는 키를 두지 않는다.
 */
public final class ExploreKeys {

    private ExploreKeys() {}

    /** 파생 키의 유일한 접두사 — <b>재구성 단위가 곧 삭제 단위</b>다. */
    static final String PREFIX = "explore:";

    /** 워밍업 완료 플래그. <b>부재가 곧 폴백 조건</b>이다 — 반쯤 채워진 인덱스로 목록을 내리면 방이 사라진다. */
    public static final String WARMED = PREFIX + "ready";

    /**
     * 전체 인기 ZSET. 대상은 공개 그룹 챌린지의 UPCOMING·ACTIVE 뿐이다.
     *
     * <p>정렬 ZSET({@code explore:z:*})과 <b>접두사를 달리한다.</b> 둘 다 ZSET 이지만 읽는 법이
     * 정반대다 — 정렬은 score 를 0 으로 두고 멤버 사전순으로 페이징하고, 인기는 score 로 Top N 을
     * 뽑는다. 한 이름 아래 두면 어느 쪽 규칙이 적용되는지 키만 보고 알 수 없다.
     */
    public static final String TRENDING_ALL = PREFIX + "t:all";

    /** 카테고리별 인기 ZSET. */
    public static String trendingCategory(String category) {
        return PREFIX + "t:cat:" + category;
    }

    /** 정렬 6종의 ZSET. 정렬마다 키가 갈리는 이유는 멤버에 정렬 키가 박혀 있기 때문이다. */
    public static String sorted(ExploreSort sort) {
        return PREFIX + "z:" + sort.name();
    }

    /** 방 표시값 HASH — 참여자 수·완주율·유지율·인기 점수. */
    public static String statsByHex(String hex) {
        return PREFIX + "h:" + hex;
    }

    public static String stats(UUID challengeId) {
        return PREFIX + "h:" + hex(challengeId);
    }

    /** 노출 후보 집합 — 공개·그룹·UPCOMING/ACTIVE·미삭제. 노출 제외를 멤버십으로 표현한다. */
    public static final String VISIBLE = PREFIX + "s:visible";

    /** 카테고리 필터 집합. */
    public static String category(String category) {
        return PREFIX + "s:cat:" + category;
    }

    /** 인증 방식 필터 집합(AUTO / MANUAL). */
    /** 파생 인덱스를 마지막으로 계산한 시각. 인기 응답의 calculatedAt 이 이 값이다. */
    public static final String CALCULATED_AT = PREFIX + "calculated_at";

    /** 카테고리별 진행 중 공개 그룹 수. 10분 집계가 갱신하고 모든 인스턴스가 같은 값을 본다. */
    public static final String CATEGORY_COUNTS = PREFIX + "cnt:category";

    /** 5분 보정 잠금 — 여러 인스턴스가 같은 회차를 겹쳐 돌지 않게. */
    public static final String SWEEP_LOCK = PREFIX + "sweep_lock";

    /** 마지막으로 성공한 보정 시각. 다음 회차가 「그 뒤에 움직인 방」을 찾는 기준. */
    public static final String SWEPT_AT = PREFIX + "swept_at";

    /** 마지막으로 값까지 대조한 시각 — 구조 대조보다 드물게 도는 검사의 기준. */
    public static final String VERIFIED_AT = PREFIX + "verified_at";

    /** 전수 재구성 잠금. 여러 인스턴스가 동시에 비우고 채우면 서로의 중간 상태를 지운다. */
    public static final String REBUILD_LOCK = PREFIX + "rebuild_lock";

    /** 전수 재구성이 실제로 수행된 횟수. 잠금에 막힌 호출은 세지 않는다 — 관측·테스트용. */
    public static final String RECONCILE_RUNS = PREFIX + "reconcile_runs";

    public static String verifyType(String verifyType) {
        return PREFIX + "s:verify:" + verifyType;
    }

    // 표본 미달 방을 담는 별도 집합은 두지 않는다 — 그런 방은 애초에 해당 정렬 ZSET 의 멤버가
    // 아니므로(ExploreIndexer.memberFor 가 null 을 준다) 집합으로 한 번 더 거를 것이 없다.

    public static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    public static UUID fromHex(String hex) {
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }
}
