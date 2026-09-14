package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 탐색 파생 데이터의 Redis 접근 (탐색 테크스펙 5-1 · 5-3).
 *
 * <p><b>여기 있는 것은 전부 파생이다.</b> 원천은 MySQL 이고 이 키들은 언제든 다시 만들 수 있다 —
 * 그래서 소실을 전제로 설계하고, 영속화 설정에 기대지 않으며, 이상하면 접두사째 지우고 워밍업을
 * 다시 돌린다.
 *
 * <p>이 클래스는 <b>예외를 삼키지 않는다.</b> 회로차단기가 판단해야 하므로 실패는 그대로
 * 올려 보낸다 — 여기서 조용히 빈 결과를 돌려주면 "Redis 가 죽었는데 방이 하나도 없다"가 되어
 * 폴백이 동작하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ExploreRedisStore {

    /**
     * 정렬 ZSET 은 score 를 쓰지 않는다 — 순서는 전부 멤버 문자열이 결정한다.
     * 자세한 이유는 {@link SortKeyCodec} 참고.
     */
    private static final double LEX_SCORE = 0d;

    private final StringRedisTemplate redis;

    // ===== 워밍업 플래그 =====

    public boolean isWarmed() {
        return Boolean.TRUE.equals(redis.hasKey(ExploreKeys.WARMED));
    }

    public void markWarmed() {
        redis.opsForValue().set(ExploreKeys.WARMED, "1");
    }

    // ===== 인기 랭킹 =====

    /** 인기 Top N. 점수(24시간 신규 참여자 수)를 함께 돌려준다 — 카드에 그대로 표시된다. */
    public List<TrendingEntry> topTrending(String category, int limit) {
        String key = (category == null) ? ExploreKeys.TRENDING_ALL : ExploreKeys.trendingCategory(category);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        if (tuples == null) return List.of();

        List<TrendingEntry> entries = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() == null) continue;
            // score 는 「참여자 수 · 동점 보정」을 함께 담은 합성값이다. 카드에 보여줄 것은
            // 참여자 수뿐이므로 상위 자리만 꺼낸다 — 그대로 쓰면 수억이 찍힌다.
            entries.add(new TrendingEntry(ExploreKeys.fromHex(t.getValue()),
                    t.getScore() == null ? 0 : recentJoinsOf(t.getScore())));
        }
        return entries;
    }

    public record TrendingEntry(UUID challengeId, int recentJoins24h) {}

    /** 합성 score 에서 참여자 수만 꺼낸다. */
    static int recentJoinsOf(double score) {
        return (int) ((long) score / TIE_BREAK_RANGE);
    }

    /**
     * 인기 점수를 지금 값으로 덮는다. 증분(+1)이 아니라 절대값인 이유는 <b>인덱서가 24시간 창을
     * 매번 다시 세기</b> 때문이다 — 증분으로 쌓으면 창을 벗어난 참여를 뺄 방법이 없다.
     * 전체와 카테고리 ZSET 을 함께 쓴다. 둘이 어긋나면 카테고리 탭과 홈의 순위가 달라진다.
     */
    public void setTrending(UUID challengeId, String category, int recentJoins24h, Long lastJoinedMillis) {
        String member = ExploreKeys.hex(challengeId);
        double score = trendingScore(recentJoins24h, lastJoinedMillis);
        redis.opsForZSet().add(ExploreKeys.TRENDING_ALL, member, score);
        if (category != null) redis.opsForZSet().add(ExploreKeys.trendingCategory(category), member, score);
    }

    /**
     * 인기 점수 인코딩 — <b>신규 참여자 수 DESC → 마지막 참여 시각 DESC</b>(공통 5-3).
     *
     * <p>점수에 참여자 수만 넣으면 동점일 때 ZSET 이 member(=challengeId)로 정렬한다. 그러면
     * 「같은 인원이면 더 최근에 몰린 쪽이 위」라는 규칙이 <b>UUID 순서</b>로 바뀐다 — 규칙이
     * 있으나 마나 하고, 순서가 무작위로 보인다.
     *
     * <p>두 값을 한 double 에 쌓는다. 상위 자리가 참여자 수, 하위 자리가 기준 시각 이후 초다.
     * ZSET score 는 double 이라 정수부 2^53 까지 정확하므로, 참여자 수 × 2^31 + 초 는 안전하다.
     * 시각을 초로 줄이는 것은 정밀도를 아끼기 위함이고, 같은 초에 몰린 방들의 순서까지는
     * 보장하지 않는다 — 거기까지는 규칙이 정하지 않는다.
     */
    static double trendingScore(int recentJoins24h, Long lastJoinedMillis) {
        long seconds = (lastJoinedMillis == null) ? 0L
                : Math.clamp((lastJoinedMillis - TRENDING_EPOCH_MILLIS) / 1000L, 0L, TIE_BREAK_RANGE - 1);
        return (double) recentJoins24h * TIE_BREAK_RANGE + seconds;
    }

    /** 동점 보정에 쓰는 기준 시각(2020-01-01T00:00:00Z). 이 이전 시각은 0 으로 접는다. */
    private static final long TRENDING_EPOCH_MILLIS = 1_577_836_800_000L;

    /** 동점 보정 자리의 폭(초). 2^31 초 ≈ 68년이라 기준 시각 이후를 충분히 덮는다. */
    private static final long TIE_BREAK_RANGE = 2_147_483_648L;

    public void removeTrending(UUID challengeId, String category) {
        String member = ExploreKeys.hex(challengeId);
        redis.opsForZSet().remove(ExploreKeys.TRENDING_ALL, member);
        if (category != null) redis.opsForZSet().remove(ExploreKeys.trendingCategory(category), member);
    }

    // ===== 정렬 ZSET =====

    /**
     * 커서 이후 구간을 사전순으로 읽는다. {@code ZRANGEBYLEX} 한 번이며,
     * 이것이 MySQL keyset 페이징의 부등호 조건과 <b>같은 의미</b>다.
     *
     * @param afterMember 직전 페이지의 마지막 멤버(배타). 첫 페이지면 null
     */
    public List<String> sortedRange(ExploreSort sort, String afterMember, int limit) {
        // (afterMember, +inf) — 하한만 배타로 두고 상한은 열어 둔다.
        // leftOpen(from, null) 로는 상한이 null 인 Range 가 만들어져 ZRANGEBYLEX 인자가 깨진다.
        Range<String> lexRange = (afterMember == null)
                ? Range.unbounded()
                : Range.of(Range.Bound.exclusive(afterMember), Range.Bound.unbounded());
        Set<String> members = redis.opsForZSet().rangeByLex(
                ExploreKeys.sorted(sort), lexRange, Limit.limit().offset(0).count(limit));
        return (members == null) ? List.of() : new ArrayList<>(members);
    }

    /** 멤버 교체 — 정렬값이 바뀌면 예전 멤버를 지우고 새로 넣는다(멤버에 값이 박혀 있으므로). */
    public void replaceSorted(ExploreSort sort, String oldMember, String newMember) {
        String key = ExploreKeys.sorted(sort);
        if (oldMember != null) redis.opsForZSet().remove(key, oldMember);
        if (newMember != null) redis.opsForZSet().add(key, newMember, LEX_SCORE);
    }

    public void removeSorted(ExploreSort sort, String member) {
        if (member != null) redis.opsForZSet().remove(ExploreKeys.sorted(sort), member);
    }

    // ===== 표시 통계 HASH =====

    public Map<String, String> stats(UUID challengeId) {
        Map<Object, Object> raw = redis.opsForHash().entries(ExploreKeys.stats(challengeId));
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    public void putStats(UUID challengeId, Map<String, String> values) {
        redis.opsForHash().putAll(ExploreKeys.stats(challengeId), values);
    }

    public void deleteStats(UUID challengeId) {
        redis.delete(ExploreKeys.stats(challengeId));
    }

    // ===== 필터 SET =====

    public void addToSet(String key, UUID challengeId) {
        redis.opsForSet().add(key, ExploreKeys.hex(challengeId));
    }

    public void removeFromSet(String key, UUID challengeId) {
        redis.opsForSet().remove(key, ExploreKeys.hex(challengeId));
    }

    /**
     * 여러 id 의 집합 소속을 한 번에 묻는다 — 후보 한 건마다 왕복하면 페이지 하나에 수십 번이 된다.
     *
     * @return {@code ids} 와 같은 순서의 소속 여부
     */
    public List<Boolean> areMembers(String key, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Object[] values = ids.stream().map(ExploreKeys::hex).toArray();
        Map<Object, Boolean> hits = redis.opsForSet().isMember(key, values);
        List<Boolean> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(hits != null && Boolean.TRUE.equals(hits.get(ExploreKeys.hex(id))));
        }
        return out;
    }

    /** 접두사 전체 삭제 — 재구성 전에 부른다. 파생이라 지워도 잃는 것이 없다. */
    public void flushDerived() {
        deleteByPattern("explore:*");
        deleteByPattern("trending:*");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
