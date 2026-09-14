package com.ruleup.ruleup_backend.challenge.explore.store;

import com.ruleup.ruleup_backend.challenge.explore.ExploreSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    private final StringRedisTemplate redis;

    // ===== 워밍업 플래그 =====

    public boolean isWarmed() {
        return Boolean.TRUE.equals(redis.hasKey(ExploreKeys.WARMED));
    }

    /**
     * 파생 인덱스를 계산한 시각을 새긴다.
     *
     * <p>인기 응답의 {@code calculatedAt} 이 이 값이다. 응답을 만들 때 「지금」을 찍으면 클라가
     * 언제나 「0초 전 기준」을 보게 되어, 지연을 표시하라고 그 필드를 둔 이유가 사라진다.
     */
    public void markCalculatedAt(java.time.Instant at) {
        redis.opsForValue().set(ExploreKeys.CALCULATED_AT, at.toString());
    }

    /** 마지막 계산 시각. 아직 없으면 비어 있다. */
    public java.util.Optional<String> calculatedAt() {
        return java.util.Optional.ofNullable(redis.opsForValue().get(ExploreKeys.CALCULATED_AT));
    }

    /** 준비 상태를 내린다 — 값이 원천과 맞지 않는 회차를 「준비 완료」로 공표하지 않기 위해. */
    public void clearWarmed() {
        redis.delete(ExploreKeys.WARMED);
    }

    public void markWarmed() {
        redis.opsForValue().set(ExploreKeys.WARMED, "1");
    }

    // ===== 인기 랭킹 =====

    /** 인기 Top N. 점수(24시간 신규 참여자 수)를 함께 돌려준다 — 카드에 그대로 표시된다. */
    public List<TrendingEntry> topTrending(String category, int limit) {
        return topTrending(category, limit, 0);
    }

    /** @param offset 이미 훑은 만큼 건너뛴다 — 걸러진 후보가 많을 때 이어 읽는다. */
    public List<TrendingEntry> topTrending(String category, int limit, int offset) {
        String key = (category == null) ? ExploreKeys.TRENDING_ALL : ExploreKeys.trendingCategory(category);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, offset, offset + limit - 1L);
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

    // ===== 표시 통계 HASH =====

    /** 그 방의 표시값 HASH. 없으면 비어 있다 — 아직 투영되지 않은 방이다. */
    public Map<Object, Object> getStats(UUID challengeId) {
        return redis.opsForHash().entries(ExploreKeys.stats(challengeId));
    }

    /**
     * 표시값 HASH 를 <b>통째로 갈아 끼운다.</b>
     *
     * <p>{@code putAll} 만 쓰면 이번에 사라진 필드가 남는다 — 멤버가 빠져 표본 미달이 되어
     * 완주율이 {@code null} 로 돌아간 방이 옛 완주율을 계속 보여 준다. 표본이 모자란다는
     * 사실을 「값이 없음」으로 드러내야 하는데, 지운 적 없는 값이 그 자리를 덮는다.
     *
     * <p>지우고 쓰는 사이에 읽으면 빈 HASH 가 보이므로 한 번의 트랜잭션으로 묶는다. 조회는
     * HASH 가 비면 그 방을 후보에서 빼므로, 깜빡이는 대신 잠깐 목록에서 빠지는 쪽이 낫다.
     */
    public void putStats(UUID challengeId, Map<String, String> values) {
        String key = ExploreKeys.stats(challengeId);
        redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.multi();
            byte[] rawKey = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.keyCommands().del(rawKey);
            values.forEach((field, value) -> connection.hashCommands().hSet(rawKey,
                    field.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            connection.exec();
            return null;
        });
    }

    /** 카테고리 수 HASH 전체. 비어 있으면 아직 집계 전이다. */
    public Map<Object, Object> categoryCounts() {
        return redis.opsForHash().entries(ExploreKeys.CATEGORY_COUNTS);
    }

    /**
     * 카테고리 수를 통째로 갈아 끼운다 — 사라진 카테고리가 옛 수치로 남지 않게.
     *
     * <p><b>지우고 쓰지 않는다.</b> 그 사이에 실패하면 직전 값까지 함께 잃어, 「직전 값을 유지한다」는
     * 약속이 깨지고 조회가 매번 직접 집계로 내려간다. 임시 키에 다 쓴 뒤 {@code RENAME} 으로
     * 한 번에 바꿔 끼운다 — 실패하면 옛 키가 그대로 남는다.
     */
    public void putCategoryCounts(Map<String, String> counts) {
        if (counts.isEmpty()) return;
        String staging = ExploreKeys.CATEGORY_COUNTS + ":staging";
        redis.delete(staging);
        redis.opsForHash().putAll(staging, counts);
        redis.rename(staging, ExploreKeys.CATEGORY_COUNTS);
    }

    /**
     * 한 방의 투영을 <b>한 번에</b> 갈아 끼운다 (탐색 백엔드 3-2 「Lua 로 원자 갱신」).
     *
     * <p>HASH·정렬 ZSET 6종·노출/필터 SET·인기 ZSET 을 따로 쓰면 중간에 끊겼을 때 구조마다
     * 다른 시점의 값이 남는다 — 정렬은 새 값으로 서 있는데 카드 숫자는 옛 값인 식이다.
     * 한 스크립트 안에서 바꾸면 그 상태가 아예 생기지 않는다.
     *
     * <p>순서는 <b>(원천 리비전, 계산 리비전)</b> 두 값이 정한다. 재계산은 여러 경로에서 병렬로
     * 들어오고(커밋 후 갱신·5분 스윕·03:30 대조), 느린 회차가 늦게 도착해 최신 값을 되돌리면
     * 「참여자 수가 줄었다 늘었다」 한다.
     *
     * <p><b>원천 리비전만으로는 부족하다.</b> 같은 리비전에서 나온 결과라고 값까지 같지는 않다 —
     * 24시간 가입 수는 원천이 그대로여도 시간이 지나기만 하면 내려가고, {@code challenge_stats}
     * 보정은 완주율을 바꾸면서 리비전에는 흔적을 남기지 않으며, {@code Timestamp#getTime()} 이
     * MySQL 의 마이크로초를 밀리초로 자르는 탓에 서로 다른 변경이 같은 리비전으로 겹치기도 한다.
     * 그래서 같은 리비전 안에서는 <b>누가 더 늦게 읽었는가</b>로 가른다.
     *
     * @param calcRevision 이 값을 계산한 읽기의 기준 시각(DB 의 {@code NOW(6)}). 24시간 창도 이
     *                     시각으로 잘리므로, 결과의 신선도를 그대로 나타내는 수다
     * @return 실제로 반영됐으면 true, 더 새 값이 이미 있어 버렸으면 false
     */
    public boolean applyProjection(UUID challengeId, long version, long calcRevision,
                                   Map<String, String> stats,
                                   List<String[]> sortedSpecs,
                                   List<String> setKeysToAdd,
                                   List<String> setKeysToRemove,
                                   Map<String, Double> trendingAdds,
                                   List<String> trendingRemoves) {
        String member = ExploreKeys.hex(challengeId);
        List<String> keys = List.of(ExploreKeys.stats(challengeId));
        List<String> args = new java.util.ArrayList<>();
        args.add(String.valueOf(version));
        args.add(String.valueOf(calcRevision));
        args.add(member);

        // 정렬 구간은 (ZSET 키, HASH 필드, 새 멤버) 세 쌍이다. <b>이전 멤버는 넘기지 않는다</b> —
        // 스크립트가 HASH 에서 직접 읽는다. 밖에서 읽어 넘기면 두 갱신이 같은 이전 값을 읽고,
        // 나중 것이 중간 갱신이 넣은 멤버를 지우지 못해 같은 방이 ZSET 에 두 번 남는다.
        args.add(String.valueOf(sortedSpecs.size()));
        for (String[] spec : sortedSpecs) { args.add(spec[0]); args.add(spec[1]); args.add(spec[2]); }
        args.add(String.valueOf(stats.size()));
        stats.forEach((k, v) -> { args.add(k); args.add(v); });
        args.add(String.valueOf(setKeysToAdd.size()));
        args.addAll(setKeysToAdd);
        args.add(String.valueOf(setKeysToRemove.size()));
        args.addAll(setKeysToRemove);
        args.add(String.valueOf(trendingAdds.size()));
        trendingAdds.forEach((k, v) -> { args.add(k); args.add(String.valueOf(v)); });
        args.add(String.valueOf(trendingRemoves.size()));
        args.addAll(trendingRemoves);

        Long applied = redis.execute(PROJECTION_SCRIPT, keys, args.toArray());
        return applied != null && applied == 1L;
    }

    /** 투영 버전 필드 — HASH 안에 함께 둬서 값과 버전이 따로 놀 수 없게 한다. */
    public static final String VERSION_FIELD = "__v";

    /** 같은 원천 리비전 안에서의 순서를 가르는 <b>계산 시각</b> 필드. */
    public static final String CALC_FIELD = "__c";

    /**
     * 후보에서 <b>빼는</b> 것도 한 번에, 그리고 버전을 남긴다 (tombstone).
     *
     * <p>지우면서 HASH 까지 없애면 버전도 사라진다. 그 순간 동시에 돌던 오래된 공개 투영이
     * 「기록된 버전이 없다」로 읽고 그 방을 <b>다시 넣는다</b> — 비공개로 바꿨는데 목록에
     * 되살아나는, 존재 은닉이 뚫리는 경로다. 그래서 값만 비우고 버전은 남긴다.
     */
    public boolean removeProjection(UUID challengeId, long version, long calcRevision,
                                    List<String[]> sortedSpecs,
                                    List<String> setKeysToRemove,
                                    List<String> trendingRemoves) {
        return applyProjection(challengeId, version, calcRevision,
                Map.of(), sortedSpecs, List.of(), setKeysToRemove, Map.of(), trendingRemoves);
    }

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> PROJECTION_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    local statsKey   = KEYS[1]
                    -- 비교는 수로, <b>저장은 문자열 그대로</b> 한다. Lua 의 수→문자열 변환은
                    -- 유효숫자 14자리라, 마이크로초 리비전(16자리)을 수로 넘기면 지수 표기로
                    -- 뭉개져 저장된다 — 그 순간 다음 회차의 비교 기준이 틀어진다.
                    local versionStr = ARGV[1]
                    local calcStr    = ARGV[2]
                    local version    = tonumber(versionStr)
                    local calc       = tonumber(calcStr)
                    local member     = ARGV[3]
                    -- 순서는 (원천 리비전, 계산 리비전)의 사전순이다.
                    --  ① 기록된 원천 리비전이 더 새로우면 버린다 — 옛 원천이 새 원천을 덮지 못한다.
                    --  ② 같은 원천 리비전이면 <b>더 늦게 읽은 쪽</b>이 이긴다. 원천이 그대로여도
                    --     24시간 창은 시간만으로 내려가고 challenge_stats 보정은 리비전을 올리지
                    --     않으므로, 같은 리비전의 결과끼리도 신선도가 다르다.
                    local curV = redis.call('HGET', statsKey, '__v')
                    if curV then
                      local cv = tonumber(curV)
                      if cv > version then return 0 end
                      if cv == version then
                        local curC = redis.call('HGET', statsKey, '__c')
                        if curC and tonumber(curC) > calc then return 0 end
                      end
                    end

                    local i = 4
                    local function count()
                      local n = tonumber(ARGV[i]); i = i + 1; return n
                    end

                    -- 정렬: (zsetKey, hashField, newMember). 이전 멤버는 HASH 에서 직접 읽는다 —
                    -- 밖에서 읽어 넘기면 동시 갱신이 서로의 멤버를 남겨 같은 방이 두 번 선다.
                    local sortsN = count()
                    local sorts = {}
                    for k = 1, sortsN do
                      sorts[k] = { key = ARGV[i], field = ARGV[i + 1], newMember = ARGV[i + 2] }
                      i = i + 3
                    end
                    for k = 1, sortsN do
                      local old = redis.call('HGET', statsKey, sorts[k].field)
                      if old then redis.call('ZREM', sorts[k].key, old) end
                    end

                    local statsN = count()
                    local fields = {}
                    for k = 1, statsN do
                      fields[k] = { ARGV[i], ARGV[i + 1] }; i = i + 2
                    end

                    redis.call('DEL', statsKey)
                    redis.call('HSET', statsKey, '__v', versionStr)
                    redis.call('HSET', statsKey, '__c', calcStr)
                    for k = 1, statsN do
                      redis.call('HSET', statsKey, fields[k][1], fields[k][2])
                    end
                    for k = 1, sortsN do
                      if sorts[k].newMember ~= '' then
                        redis.call('ZADD', sorts[k].key, 0, sorts[k].newMember)
                        redis.call('HSET', statsKey, sorts[k].field, sorts[k].newMember)
                      end
                    end

                    local saddN = count()
                    for _ = 1, saddN do redis.call('SADD', ARGV[i], member); i = i + 1 end
                    local sremN = count()
                    for _ = 1, sremN do redis.call('SREM', ARGV[i], member); i = i + 1 end
                    local taddN = count()
                    for _ = 1, taddN do
                      redis.call('ZADD', ARGV[i], tonumber(ARGV[i + 1]), member); i = i + 2
                    end
                    local tremN = count()
                    for _ = 1, tremN do redis.call('ZREM', ARGV[i], member); i = i + 1 end
                    return 1
                    """, Long.class);

    // ===== 필터 SET =====

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
    /**
     * 전수 재구성 잠금을 잡는다. 잡으면 true.
     *
     * <p>TTL 을 둔다 — 잠근 인스턴스가 죽으면 다음 회차가 영영 못 돌게 되기 때문이다.
     * 재구성은 멱등이라 만료 뒤 겹쳐 도는 최악의 경우도 결과가 같다.
     */
    public String tryLockRebuild(java.time.Duration ttl) {
        String token = java.util.UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(ExploreKeys.REBUILD_LOCK, token, ttl));
        return acquired ? token : null;
    }

    /**
     * 내가 잡은 잠금만 푼다.
     *
     * <p>무조건 지우면, TTL 이 지나 <b>다른 인스턴스가 새로 잡은</b> 잠금을 앞 실행자가 지운다 —
     * 그 순간부터 둘이 동시에 전수 재구성을 돌며 서로의 중간 상태를 덮는다. 값이 내 토큰일 때만
     * 지우도록 비교와 삭제를 한 스크립트로 묶는다.
     */
    public String tryLockSweep(java.time.Duration ttl) {
        String token = java.util.UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(ExploreKeys.SWEEP_LOCK, token, ttl));
        return acquired ? token : null;
    }

    public void unlockSweep(String token) {
        releaseIfOwner(ExploreKeys.SWEEP_LOCK, token);
    }

    /**
     * 24시간 창에 가입이 <b>있는</b> 방들.
     *
     * <p>시간이 지나기만 해도 점수가 내려가는 방은 이 집합뿐이다 — 가입이 0건인 방은 창이
     * 흘러도 0 그대로다. 그 목록을 알아내려고 원천을 훑을 필요는 없다. 인기 점수의 상위 자리가
     * 곧 가입 수라, <b>파생 인덱스 자신이</b> 「누가 내려갈 수 있는가」를 이미 알고 있다.
     */
    public java.util.Set<String> membersWithRecentJoins() {
        java.util.Set<String> members = redis.opsForZSet()
                .rangeByScore(ExploreKeys.TRENDING_ALL, TIE_BREAK_RANGE, Double.MAX_VALUE);
        return members == null ? java.util.Set.of() : members;
    }

    /** 그 방의 인기 점수. 없으면 null — ZSET 에 들어 있지 않다는 뜻이다. */
    public Double trendingScoreOf(String key, UUID challengeId) {
        return redis.opsForZSet().score(key, ExploreKeys.hex(challengeId));
    }

    /**
     * 마지막으로 <b>값까지</b> 대조한 시각.
     *
     * <p>구조 대조(집합·ZSET 소속)는 키 수만큼만 읽으면 되지만 값 대조는 방마다 왕복이라,
     * 같은 주기로 돌릴 수 없다. 둘을 나누고 값 대조만 드물게 돌린다.
     */
    public java.util.Optional<java.time.Instant> lastDeepVerifyAt() {
        String raw = redis.opsForValue().get(ExploreKeys.VERIFIED_AT);
        if (raw == null) return java.util.Optional.empty();
        try { return java.util.Optional.of(java.time.Instant.parse(raw)); }
        catch (RuntimeException e) { return java.util.Optional.empty(); }
    }

    public void markDeepVerified(java.time.Instant at) {
        redis.opsForValue().set(ExploreKeys.VERIFIED_AT, at.toString());
    }

    /** 마지막으로 성공한 보정 시각. 없으면 비어 있다 — 그때는 전체를 본다. */
    public java.util.Optional<java.time.Instant> lastSweepAt() {
        String raw = redis.opsForValue().get(ExploreKeys.SWEPT_AT);
        if (raw == null) return java.util.Optional.empty();
        try { return java.util.Optional.of(java.time.Instant.parse(raw)); }
        catch (RuntimeException e) { return java.util.Optional.empty(); }
    }

    public void markSweptAt(java.time.Instant at) {
        redis.opsForValue().set(ExploreKeys.SWEPT_AT, at.toString());
    }

    public void unlockRebuild(String token) {
        releaseIfOwner(ExploreKeys.REBUILD_LOCK, token);
    }

    /** 값이 내 토큰일 때만 지운다 — 비교와 삭제를 한 스크립트로 묶어야 그 사이가 벌어지지 않는다. */
    private void releaseIfOwner(String key, String token) {
        if (token == null) return;
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                                + "else return 0 end", Long.class),
                java.util.List.of(key), token);
    }

    /** 전수 재구성이 실제로 수행된 횟수를 하나 올린다. */
    public void countRebuild() {
        redis.opsForValue().increment(ExploreKeys.RECONCILE_RUNS);
    }

    /** 그 집합의 멤버 전부. */
    public java.util.Set<String> membersOf(String key) {
        java.util.Set<String> members = redis.opsForSet().members(key);
        return members == null ? java.util.Set.of() : members;
    }

    /**
     * 여러 방의 <b>현재 정렬 멤버</b>를 한 번의 왕복으로 읽는다.
     *
     * <p>「지금 이 방의 정본이 무엇인가」에 답할 수 있는 것은 Redis 의 HASH 뿐이다. 원천
     * 스냅샷으로 대신 답하면 안 된다 — 스냅샷을 읽은 뒤 더 새 투영이 들어왔다면 그 답은 이미
     * 틀렸고, 틀린 답을 「정본이니 건드리지 말자」로 쓰면 유물이 그대로 남는다.
     *
     * <p>그렇다고 방마다 물으면 정렬이 여섯이라 왕복이 방 수의 여섯 배가 된다. 파이프라인으로
     * 묶어 <b>한 번에</b> 보낸다 — 명령 수는 방 수지만 왕복은 하나다.
     *
     * @return 방 id → {@code fields} 와 같은 순서의 멤버 목록(없는 필드는 {@code null})
     */
    public Map<UUID, List<String>> sortMembersOf(List<UUID> ids, List<String> fields) {
        if (ids.isEmpty() || fields.isEmpty()) return Map.of();
        byte[][] rawFields = fields.stream()
                .map(f -> f.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toArray(byte[][]::new);

        List<Object> replies = redis.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (UUID id : ids) {
                        connection.hashCommands().hMGet(
                                ExploreKeys.stats(id).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                rawFields);
                    }
                    return null;
                });

        Map<UUID, List<String>> out = new java.util.HashMap<>(ids.size());
        for (int i = 0; i < ids.size() && i < replies.size(); i++) {
            List<String> members = new ArrayList<>(fields.size());
            if (replies.get(i) instanceof List<?> values) {
                for (Object v : values) members.add(v == null ? null : String.valueOf(v));
            }
            while (members.size() < fields.size()) members.add(null);
            out.put(ids.get(i), members);
        }
        return out;
    }

    /**
     * 그 멤버가 <b>지금도</b> 정본이 아닐 때만 정렬 ZSET 에서 뺀다.
     *
     * <p>보정은 「무엇이 유물인가」를 먼저 정하고 나중에 지운다. 그 사이는 비어 있지 않다 —
     * 커밋 직후 갱신이 값을 <b>원래대로 되돌리면</b>(참여자가 들어왔다 나가면 정렬 멤버 문자열이
     * 예전 것과 똑같아진다) 방금 유물로 찍어 둔 그 멤버가 다시 정본이 된다. 그대로 지우면
     * HASH 는 그 멤버를 가리키는데 ZSET 에는 없는 상태가 되고, 그 방은 다음 원천 변경까지
     * 그 정렬에서 사라진다 — 보정이 최신을 덮지 않는다는 약속이 여기서 깨진다.
     *
     * <p>그래서 확인과 삭제를 한 스크립트로 묶는다. 판단은 언제나 <b>지우는 순간의</b> HASH 다.
     *
     * @return 실제로 뺐으면 true, 그새 정본이 되어 두었으면 false
     */
    public boolean removeSortedMemberIfStale(String statsKey, String zsetKey, String field, String member) {
        Long removed = redis.execute(STALE_MEMBER_SCRIPT, List.of(statsKey, zsetKey), field, member);
        return removed != null && removed == 1L;
    }

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> STALE_MEMBER_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                    -- 지우려는 멤버가 그새 이 방의 정본이 됐다면 손대지 않는다.
                    if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then return 0 end
                    return redis.call('ZREM', KEYS[2], ARGV[2])
                    """, Long.class);

    public java.util.Set<String> zsetMembersOf(String key) {
        java.util.Set<String> members = redis.opsForZSet().range(key, 0, -1);
        return members == null ? java.util.Set.of() : members;
    }

    /**
     * 파생 데이터를 통째로 지운다.
     *
     * <p>접두사가 <b>하나</b>라서 이 한 줄이면 된다. 인기 키만 다른 접두사를 쓰던 때에는 지우는
     * 곳마다 두 패턴을 기억해야 했고, 하나를 빠뜨리면 「비웠는데 인기만 옛 순위로 남는」 상태가
     * 됐다 — 재구성 단위와 삭제 단위가 어긋나면 그 자리가 생긴다.
     */
    public void flushDerived() {
        deleteByPattern(ExploreKeys.PREFIX + "*");
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
