package com.ruleup.ruleup_backend.challenge.explore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeStatus;
import com.ruleup.ruleup_backend.challenge.domain.ParticipationType;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.routine.domain.SelectedMethod;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 홈 실시간 인기 조회 (탐색 백엔드 테크스펙 §11-1).
 *
 * <p>순위는 {@link TrendingRankingSource}(Redis ZSET, 장애 시 MySQL) 에서, <b>표시값과 사용자별
 * 값은 DB 에서</b> 읽어 합친다. 인기에는 티어 필터를 적용하지 않는다 — 내 티어로 못 들어가는 방도
 * 보이고 {@code joinable} 로 잠금만 표시한다(정책 §3.1).
 */
@Service
@RequiredArgsConstructor
public class TrendingService {

    private final TrendingRankingSource rankingSource;
    private final ChallengeRepository challengeRepository;
    private final UserScoreSummaryRepository scoreSummaryRepository;
    private final MyMembershipReader myMembershipReader;
    private final MeterRegistry meterRegistry;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore store;
    private final com.ruleup.ruleup_backend.challenge.explore.store.ExploreCircuitBreaker circuit;
    private final AtomicLong redisServed = new AtomicLong();
    private final AtomicLong sqlServed = new AtomicLong();

    @PostConstruct
    void registerMetrics() {
        // 폴백이 조용히 상시화되는 것이 가장 나쁘다 — 비율을 늘 볼 수 있게 둔다.
        Gauge.builder("trending_redis_serve_ratio", this, TrendingService::redisServeRatio)
                .description("인기 랭킹을 Redis 로 응답한 비율 — 1 미만이면 폴백이 돌고 있다")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public TrendingResponse getTrending(UUID userId, String category) {
        String normalized = normalizeCategory(category);
        Set<UUID> blocked = blockedChallengeIds(userId);
        Tier myTier = displayTier(userId);
        // 내가 만든 방·이미 가입한 방도 인기 목록에는 그대로 남는다 — joined 로만 구분한다.
        Set<UUID> myChallengeIds = myMembershipReader.activeChallengeIds(userId);

        List<TrendingResponse.Item> items = new ArrayList<>();
        String calculatedAt = null;
        int offset = 0;

        // <b>채워질 때까지 이어 읽는다.</b> 차단·비공개 전환으로 걸러지는 수는 사용자마다 다르고,
        // 한 번만 넉넉히 읽는 방식은 그 수가 예상보다 많으면 여전히 Top 20 을 못 채운다 —
        // 아래에 보여 줄 방이 있는데도 짧은 목록이 나간다. ZSET 이 바닥나면 거기서 끝난다.
        while (items.size() < TrendingRankingSource.TOP_N && offset < MAX_SCAN) {
            TrendingRankingSource.Ranking ranking = rankingSource.ranking(normalized, offset);
            if (calculatedAt == null) {
                calculatedAt = ranking.calculatedAt();
                recordSource(ranking.source());
            }
            if (ranking.entries().isEmpty()) break;
            offset += ranking.entries().size();

            List<UUID> ids = ranking.entries().stream()
                    .map(TrendingRankingSource.Entry::challengeId)
                    .filter(id -> !blocked.contains(id))
                    .toList();
            if (ids.isEmpty()) continue;
            Map<UUID, Challenge> byId = challengeRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Challenge::getId, Function.identity()));
            // 정원 판정은 <b>요청 시점 원천</b>이다(인기 API 명세). Redis 참여자 수는 표시용이라
            // 지연될 수 있고, 그 값으로 가입 가능 여부를 정하면 마감된 방이 열린 것으로 보인다.
            Map<UUID, Long> activeCounts = activeMemberCounts(ids);

            for (TrendingRankingSource.Entry entry : ranking.entries()) {
                if (items.size() >= TrendingRankingSource.TOP_N) break;
                if (blocked.contains(entry.challengeId())) continue;
                Challenge c = byId.get(entry.challengeId());
                if (!isCurrentlyVisible(c, normalized)) continue;

                // 인기 ZSET 에 있는데 표시값이 없다 = 투영이 덜 끝났다. 그 방만 빼면 순위가 한 칸씩
                // 밀린 목록이 정상인 척 나간다 — 인기는 「서버 간 순위 동일」이 계약이라 더 나쁘다.
                // 랭킹을 읽은 <b>뒤에</b> Redis 가 끊길 수 있다. 감싸지 않으면 그 예외가 전역
                // 핸들러까지 올라가 500 이 된다 — 같은 장애인데 계약된 503 과 다른 답이 나간다.
                Map<Object, Object> stats;
                try {
                    stats = store.getStats(c.getId());
                } catch (RuntimeException e) {
                    circuit.recordFailure(e);
                    throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
                }
                if (stats.isEmpty() || stats.get(ExploreRedisStore.VERSION_FIELD) == null) {
                    throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
                }

                items.add(new TrendingResponse.Item(
                        items.size() + 1,
                        c.getId().toString(),
                        c.publicTitle(),          // 심사 중·거부면 AI 임시 제목
                        c.publicImageUrl(),       // 심사 중·거부면 기본 이미지(null)
                        c.getCategory(),
                        // 표시값은 파생 인덱스가 정본이다 — 정렬과 숫자의 출처를 하나로 묶는다.
                        intOf(stats.get("participantCount")),
                        entry.recentJoins24h(),
                        verificationType(c),
                        (c.getMinTier() != null) ? c.getMinTier().name() : null,
                        // joinable 은 티어뿐 아니라 정원도 본다 — 티어만 보면 마감된 방이
                        // 「들어갈 수 있음」으로 보여, 눌러야 막힌다.
                        joinable(myTier, c)
                                && !isFull(c, activeCounts.getOrDefault(c.getId(), 0L).intValue()),
                        myChallengeIds.contains(c.getId()),
                        c.getEndDate().toString()));
            }
        }
        // 상한까지 훑고도 못 채웠다면 <b>아직 남아 있는데 못 본 것</b>이다. 짧은 목록을 정상인
        // 척 내리면 클라는 「이게 전부」로 읽고 서버에는 아무 신호도 남지 않는다.
        if (items.size() < TrendingRankingSource.TOP_N && offset >= MAX_SCAN) {
            meterRegistry.counter("trending_scan_limit_exceeded").increment();
            throw new BusinessException(ErrorCode.EXPLORE_TEMPORARILY_UNAVAILABLE);
        }
        if (calculatedAt == null) calculatedAt = rankingSource.ranking(normalized, 0).calculatedAt();
        return new TrendingResponse(calculatedAt, items);
    }

    /**
     * 훑기 상한. 차단이 아무리 많아도 무한히 읽지 않는다 — 여기 닿으면 목록이 짧을 수 있지만,
     * 그건 실제로 보여 줄 방이 거의 없다는 뜻이다.
     */
    private static final int MAX_SCAN = TrendingRankingSource.TOP_N * 30;

    /** 요청 시점의 ACTIVE 멤버 수 — 가입 가능 여부의 근거는 파생값이 아니라 원천이다. */
    private Map<UUID, Long> activeMemberCounts(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.stream().map(id -> (Object) uuidBytes(id)).toArray();
        Map<UUID, Long> counts = new java.util.HashMap<>();
        jdbc.query("SELECT challenge_id, COUNT(*) FROM challenge_members "
                        + "WHERE status = 'ACTIVE' AND challenge_id IN (" + placeholders + ") "
                        + "GROUP BY challenge_id",
                rs -> {
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(rs.getBytes(1));
                    counts.put(new UUID(bb.getLong(), bb.getLong()), rs.getLong(2));
                }, args);
        return counts;
    }

    private void recordSource(ExploreDataSource source) {
        if (source == ExploreDataSource.REDIS) redisServed.incrementAndGet();
        else sqlServed.incrementAndGet();
    }

    private double redisServeRatio() {
        long redis = redisServed.get();
        long total = redis + sqlServed.get();
        return total == 0 ? 0d : (double) redis / total;
    }

    /**
     * 랭킹은 파생 스냅샷이므로 응답 직전에 존재 은닉 조건을 다시 확인한다.
     * 공개 범위·모드·상태·카테고리는 인덱스가 따라오기 전에도 바뀔 수 있다.
     */
    private boolean isCurrentlyVisible(Challenge c, String category) {
        if (c == null || c.getDeletedAt() != null) return false;
        if (c.getParticipationType() != ParticipationType.GROUP || !"PUBLIC".equals(c.getVisibility())) {
            return false;
        }
        if (c.getStatus() != ChallengeStatus.UPCOMING && c.getStatus() != ChallengeStatus.ACTIVE) {
            return false;
        }
        return category == null || category.equals(c.getCategory());
    }

    /** 표시 티어가 최소 티어 이상인가 — 잠금 아이콘용이며 목록에서 빼는 데는 쓰지 않는다. */
    private boolean joinable(Tier myTier, Challenge c) {
        return c.getMinTier() == null || myTier.ordinal() >= c.getMinTier().ordinal();
    }

    private String verificationType(Challenge c) {
        var config = c.getVerificationConfig();
        return (config != null && config.selectedMethod() == SelectedMethod.AUTO) ? "AUTO" : "MANUAL";
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String code = category.trim().toUpperCase();
        if (!InterestCategory.allValid(List.of(code)))
            throw new BusinessException(ErrorCode.INVALID_FILTER_VALUE);
        return code;
    }

    /** 내가 차단한 챌린지 id. 신고하면 자동 등재되며, 본인이 풀 때까지 내 화면에서 빠진다. */
    private Set<UUID> blockedChallengeIds(UUID userId) {
        return new java.util.HashSet<>(jdbc.query(
                "SELECT target_id FROM user_blocks WHERE blocker_id = ? AND target_type = 'CHALLENGE'",
                (rs, i) -> {
                    byte[] raw = rs.getBytes(1);
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
                    return new UUID(bb.getLong(), bb.getLong());
                }, uuidBytes(userId)));
    }

    private static byte[] uuidBytes(UUID id) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }

    private Tier displayTier(UUID userId) {
        Tier tier = scoreSummaryRepository.findById(userId).map(s -> s.getDisplayTier()).orElse(Tier.BRONZE);
        return (tier == Tier.UNRANKED) ? Tier.BRONZE : tier;
    }
    private static boolean isFull(Challenge c, int participants) {
        return c.getMaxParticipants() != null && participants >= c.getMaxParticipants();
    }

    private static int intOf(Object raw) {
        try { return raw == null ? 0 : Integer.parseInt(raw.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

}
