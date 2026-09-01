package com.ruleup.ruleup_backend.challenge.explore;

/**
 * 목록을 만든 저장소 (탐색 테크스펙 5-4 {@code CURSOR_INVALID}).
 *
 * <p><b>커서에 이 값을 새기는 이유</b>: 폴백 진입·복귀는 페이지 사이에서도 일어난다. 1페이지를
 * Redis 로 내리고 2페이지를 MySQL 로 내리면 커서에 담긴 기준값의 의미가 서로 달라
 * <b>방이 중복되거나 통째로 건너뛰어진다</b>. 조용히 어긋난 목록을 주는 것보다 400 을 내고
 * 첫 페이지부터 다시 받게 하는 편이 낫다 — 사용자에게는 목록이 한 번 새로고침되는 것으로 보인다.
 */
public enum ExploreDataSource {
    /** Redis ZSET 사전순 — 정상 경로. */
    REDIS,
    /** MySQL keyset — 폴백 경로. */
    MYSQL
}
