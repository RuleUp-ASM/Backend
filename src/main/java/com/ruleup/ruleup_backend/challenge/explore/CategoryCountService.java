package com.ruleup.ruleup_backend.challenge.explore;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
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
 * <p>집계 대상은 {@code PUBLIC + GROUP + ACTIVE} 뿐이다. 비공개 방은 <b>카운트로도</b> 존재가 새면
 * 안 되므로 제외하고, 시작 전(UPCOMING) 방도 세지 않는다 — 인기·목록이 UPCOMING 을 포함하는 것과
 * 다른 이 비대칭은 의도된 것이다. 그리드는 "지금 돌아가고 있는 방이 몇 개인가"를 보여준다.
 *
 * <p>표시용 수치이고 정렬·가입 판정에 쓰지 않으므로 10분 지연을 허용한다(별도 테이블 없이 GROUP BY).
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
                        "WHERE mode = 'GROUP' AND visibility = 'PUBLIC' AND status = 'ACTIVE' " +
                        "  AND deleted_at IS NULL " +
                        "GROUP BY category",
                rs -> { counts.put(rs.getString(1), rs.getInt(2)); });

        List<CategoryGridResponse.Item> items = Arrays.stream(InterestCategory.values())
                .map(c -> new CategoryGridResponse.Item(
                        c.name(), c.getLabel(), counts.getOrDefault(c.name(), 0)))
                .toList();
        return new CategoryGridResponse(items);
    }
}
