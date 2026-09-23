package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 인기 랭킹 원천 — <b>Redis ZSET 하나</b> (탐색 테크스펙 5-1 · 5-3).
 *
 * <h4>왜 인스턴스 로컬 캐시를 걷어냈나</h4>
 * 예전에는 Caffeine 이었다. 인스턴스마다 캐시가 따로라 <b>서버가 두 대면 같은 사용자가 새로고침할
 * 때마다 다른 순위를 본다.</b> 게다가 무효화가 자기 인스턴스에만 닿아 "방금 참여했는데 인기에
 * 안 뜬다"가 인스턴스 운에 따라 갈렸다. 순위는 공유 저장소가 소유해야 한다.
 *
 * <h4>비어 있음을 정상으로 취급하지 않는다</h4>
 * Redis 가 비었거나 워밍업 전이면 <b>빈 목록을 그대로 내리지 않고</b> 503 을 낸다. 인기 섹션이
 * 조용히 사라지는 것이 가장 나쁜 실패 방식이기 때문이다.
 *
 * <h4>다른 저장소로 대신 내리지 않는다</h4>
 * 인기는 「서버 간 순위 동일」이 계약이다(공통 5-2). MySQL 스냅샷으로 대신 채우면 그 계약이
 * 깨지고 장애가 200 뒤에 숨는다. 게다가 탐색 조회 경로는 {@code challenge_stats} 를 읽지
 * 않기로 돼 있다(DB 설계 1) — 폴백이 곧 그 경계를 넘는 두 번째 출처였다.
 *
 * <p><b>캐시에 넣는 것은 랭킹 정보뿐</b>이다 — challengeId·recentJoins24h. {@code joinable}·
 * {@code participantCount} 같은 값은 보는 사람과 현재 상태에 따라 달라지므로 공용 저장소에 넣지
 * 않고 요청 시 DB 에서 다시 읽는다. 사용자별 값이 남의 화면에 새는 것을 막는 경계다.
 */
@Component
@RequiredArgsConstructor
public class TrendingRankingSource {

    /** 서버는 Top 20 을 만들고 홈은 그중 5개를 쓴다 — 상세 진입 유도를 위해 여유를 둔다. */
    public static final int TOP_N = 20;

    /**
     * ZSET 에서 한 번에 읽어 오는 개수.
     *
     * <p>Top 20 을 채우려면 <b>20 보다 많이</b> 읽어야 한다. 차단·최종 가시성 검사는 사용자마다
     * 다르고 응답 조립 시점에야 알 수 있어서, 딱 20 개만 읽으면 걸러진 만큼 목록이 짧아진다.
     *
     * <p>고정값으로 한 번만 더 읽는 것으로는 부족하다 — 차단이 그보다 많으면 여전히 못 채운다.
     * 호출부가 <b>채워질 때까지 이어 읽도록</b> 페이지 단위로 내주고, ZSET 이 바닥나면 거기서 끝난다.
     */
    private static final int PAGE = TOP_N * 3;

    private final ExploreRedisStore store;
    private final ExploreCircuitBreaker circuit;

    /** 랭킹 한 줄. 카드 표시값은 담지 않는다. */
    public record Entry(UUID challengeId, int recentJoins24h) {}

    public record Ranking(String calculatedAt, List<Entry> entries, ExploreDataSource source) {}

    /** 카테고리별(또는 전체) 인기 Top 20. */
    public Ranking ranking(String category) {
        return ranking(category, 0);
    }

    /**
     * @param offset 이미 훑은 만큼 건너뛴다. 걸러진 후보가 많으면 호출부가 이어서 더 읽는다.
     */
    public Ranking ranking(String category, int offset) {
        // 인기는 「서버 간 순위 동일」이 계약이다(공통 5-2). 다른 저장소로 대신 내리면 그 계약이
        // 깨지고, 장애가 200 뒤에 숨는다. 준비되지 않은 구간은 드러내고 잠시 뒤 받게 한다.
        if (circuit.isOpen()) {
            throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
        }
        // Redis 를 만지는 일은 <b>전부</b> 이 안에 둔다. 하나라도 밖에 두면 그 호출에서 연결이
        // 끊겼을 때 계약된 503 이 아니라 전역 500 이 나간다 — 같은 장애가 호출 순서에 따라 다른
        // 코드로 보이는 셈이다.
        List<ExploreRedisStore.TrendingEntry> top;
        String calculatedAt;
        try {
            if (!store.isWarmed()) {
                throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
            }
            top = store.topTrending(category, PAGE, offset);
            // 워밍업이 끝난 뒤의 빈 후보는 <b>정말 방이 없다</b>는 뜻이다. 다른 저장소로 대신
            // 채우면 「인기 섹션을 숨긴다」는 클라 분기가 영영 돌지 않는다(공통 5-5-1).
            // 계산 시각이 없다는 것은 인덱스가 언제 만들어졌는지 모른다는 뜻이다. 「지금」을
            // 지어내면 클라가 늘 최신으로 표시해, 지연을 드러내라고 둔 필드가 거짓을 말한다.
            calculatedAt = store.calculatedAt().orElseThrow(() ->
                    new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            circuit.recordFailure(e);
            throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
        }

        return new Ranking(calculatedAt,
                top.stream().map(t -> new Entry(t.challengeId(), t.recentJoins24h())).toList(),
                ExploreDataSource.REDIS);
    }

}
