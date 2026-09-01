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

    private static final String PREFIX = "explore:";

    /** 워밍업 완료 플래그. <b>부재가 곧 폴백 조건</b>이다 — 반쯤 채워진 인덱스로 목록을 내리면 방이 사라진다. */
    public static final String WARMED = "trending:warmed";

    /** 전체 인기 ZSET. 대상은 공개 그룹 챌린지의 UPCOMING·ACTIVE 뿐이다. */
    public static final String TRENDING_ALL = "trending:all";

    /** 카테고리별 인기 ZSET. */
    public static String trendingCategory(String category) {
        return "trending:cat:" + category;
    }

    /** 정렬 6종의 ZSET. 정렬마다 키가 갈리는 이유는 멤버에 정렬 키가 박혀 있기 때문이다. */
    public static String sorted(ExploreSort sort) {
        return PREFIX + "z:" + sort.name();
    }

    /** 방 표시값 HASH — 참여자 수·완주율·유지율·인기 점수. */
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
