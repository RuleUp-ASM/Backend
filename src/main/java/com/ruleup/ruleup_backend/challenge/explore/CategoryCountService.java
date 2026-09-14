package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 카테고리 그리드 카운트 (탐색 백엔드 테크스펙 §10).
 *
 * <p>집계 대상은 {@code PUBLIC + GROUP + (UPCOMING | ACTIVE)} 다. 비공개 방은 <b>카운트로도</b>
 * 존재가 새면 안 되므로 제외한다. 시작 전(UPCOMING)을 포함하는 이유는 그리드의 수가 곧
 * <b>"지금 들어갈 수 있는 방이 몇 개인가"</b>이기 때문이다 — 시작 전 방도 가입할 수 있고 인기·목록에도
 * 이미 나오는데 그리드에서만 빠지면, 방을 만들어도 다음 날 활성화 배치가 돌기 전까지 수가 그대로라
 * "업데이트가 안 된다"로 보인다. 이제 그리드·인기·목록의 후보 조건이 같다.
 *
 * <p>표시용 수치이고 정렬·가입 판정에 쓰지 않으므로 짧은 캐시 지연을 허용한다(별도 테이블 없이 GROUP BY).
 * 대신 집계 대상이 실제로 바뀌는 지점(생성·상태 전환·삭제)에서 {@link ChallengeGridChanged} 로 즉시 버린다.
 */
@Service
@RequiredArgsConstructor
public class CategoryCountService {

    public static final String CACHE = "challengeCategories";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CategoryCountService.class);

    private final JdbcTemplate jdbc;
    private final com.ruleup.ruleup_backend.challenge.explore.store.ExploreRedisStore store;

    /**
     * 카테고리 그리드. <b>10분 집계가 채운 Redis HASH</b>를 읽는다(공통 5-3 · 백엔드 7-3).
     *
     * <p>요청마다 MySQL 을 GROUP BY 하고 인스턴스 로컬 캐시에 담으면 인스턴스마다 다른 수가
     * 보인다 — 같은 화면을 새로고침했을 뿐인데 숫자가 오르내린다. 모든 인스턴스가 같은 값을
     * 보려면 캐시가 아니라 <b>공유 저장소</b>여야 한다.
     *
     * <p>HASH 가 아직 없으면(집계 전·Redis 장애) 직접 집계로 내려간다. 스펙이 카테고리 수만은
     * 「필요 시 challenges 직접 집계로 제한 대응」이라고 허용한 지점이다 — 목록·인기와 달리
     * 순위가 걸려 있지 않아, 조금 낡은 수를 보여 주는 편이 그리드를 통째로 비우는 것보다 낫다.
     */
    public CategoryGridResponse getCategories() {
        try {
            Map<Object, Object> cached = store.categoryCounts();
            if (!cached.isEmpty()) {
                return grid(key -> {
                    Object raw = cached.get(key);
                    try { return raw == null ? 0 : Integer.parseInt(raw.toString()); }
                    catch (NumberFormatException e) { return 0; }
                });
            }
        } catch (RuntimeException e) {
            // Redis 가 죽으면 읽기 자체가 예외다 — 잡지 않으면 폴백에 닿지도 못하고 500 이 된다.
            log.warn("카테고리 수 조회 실패 — 직접 집계로 내려간다: {}", e.toString());
        }
        Map<String, Integer> fallback = countFromSource();
        return grid(key -> fallback.getOrDefault(key, 0));
    }

    /** 10분마다 — 모든 인스턴스가 같은 수를 보도록 공유 저장소에 새겨 둔다. */
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void refreshCounts() {
        try {
            // 12종을 <b>전부</b> 쓴다. 집계 결과에 없는 카테고리를 빼면 그 자리에 옛 수치가
            // 남고, 모든 카테고리가 0 이 된 경우에는 아무것도 쓰지 않아 화면이 옛 수를 계속 보여 준다.
            Map<String, Integer> fresh = countFromSource();
            Map<String, String> counts = new HashMap<>();
            for (InterestCategory c : InterestCategory.values()) {
                counts.put(c.name(), String.valueOf(fresh.getOrDefault(c.name(), 0)));
            }
            store.putCategoryCounts(counts);
        } catch (RuntimeException e) {
            // 실패해도 직전 값이 남아 있고, 없으면 조회가 직접 집계로 내려간다.
            log.warn("카테고리 수 집계 실패 — 직전 값을 유지한다: {}", e.toString());
        }
    }

    private CategoryGridResponse grid(java.util.function.ToIntFunction<String> countOf) {
        List<CategoryGridResponse.Item> items = Arrays.stream(InterestCategory.values())
                .map(c -> new CategoryGridResponse.Item(
                        c.name(), c.getLabel(), countOf.applyAsInt(c.name())))
                .toList();
        return new CategoryGridResponse(items);
    }

    private Map<String, Integer> countFromSource() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT category, COUNT(*) FROM challenges " +
                        // 진행 중인 방만 센다. 인기·목록은 모집 중(UPCOMING) 방을 포함하지만
                        // 카테고리 수는 그렇지 않다 — 스펙이 「의도된 비대칭」이라 못 박았다(공통 3절).
                        "WHERE mode = 'GROUP' AND visibility = 'PUBLIC' " +
                        "  AND status = 'ACTIVE' " +
                        "  AND deleted_at IS NULL " +
                        "GROUP BY category",
                rs -> { counts.put(rs.getString(1), rs.getInt(2)); });
        return counts;
    }

    /**
     * 집계 대상(PUBLIC+GROUP+ACTIVE)이 실제로 바뀐 직후 캐시를 버린다 — 상태 전환 배치가 호출한다.
     * TTL 만 믿으면 방이 시작·종료돼도 화면의 수가 한참 그대로라 "업데이트가 느리다"로 보인다.
     *
     * <p>Caffeine 은 인스턴스 로컬이라 이 호출은 <b>자기 인스턴스만</b> 비운다. 다른 인스턴스는
     * TTL(1분)로 따라오므로, 다중 인스턴스에서의 최대 지연은 배치 주기(1분) + TTL(1분)이다.
     */
    @CacheEvict(value = CACHE, key = "'grid'")
    public void evict() {
        // @CacheEvict 만 수행 — 본문 없음
    }
}
