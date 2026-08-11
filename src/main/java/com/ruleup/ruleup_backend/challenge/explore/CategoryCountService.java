package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    private final JdbcTemplate jdbc;

    @Cacheable(value = CACHE, key = "'grid'")
    public CategoryGridResponse getCategories() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT category, COUNT(*) FROM challenges " +
                        "WHERE mode = 'GROUP' AND visibility = 'PUBLIC' " +
                        "  AND status IN ('UPCOMING', 'ACTIVE') " +
                        "  AND deleted_at IS NULL " +
                        "GROUP BY category",
                rs -> { counts.put(rs.getString(1), rs.getInt(2)); });

        List<CategoryGridResponse.Item> items = Arrays.stream(InterestCategory.values())
                .map(c -> new CategoryGridResponse.Item(
                        c.name(), c.getLabel(), counts.getOrDefault(c.name(), 0)))
                .toList();
        return new CategoryGridResponse(items);
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
