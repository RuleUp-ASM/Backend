package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 홈 카테고리 그리드(탐색 §2.2). 카테고리 정적 목록(InterestCategory 15종) + 진행 중 챌린지 수.
 *
 * <p>진행 중 수는 조회 시점 GROUP BY 를 Caffeine 캐시(TTL 10분)로 감싼다 — 표시용 수치라 지연 허용.
 * categoryId 는 enum 순서(ordinal+1), name 은 한글 label. 카테고리는 챌린지에 name 문자열로 저장돼 있다.
 * (@Cacheable 은 컨트롤러(다른 빈)에서 호출되므로 self-invocation 무력화 문제 없음.)
 */
@Service
@RequiredArgsConstructor
public class ChallengeCategoryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChallengeRepository challengeRepository;

    @Transactional(readOnly = true)
    @Cacheable(value = "challengeCategories", key = "'grid'")
    public CategoryGridResponse getCategories() {
        Map<String, Integer> countByCategory = new HashMap<>();
        for (Object[] row : challengeRepository.countActiveByCategory(LocalDate.now(KST))) {
            countByCategory.put((String) row[0], ((Number) row[1]).intValue());
        }
        List<CategoryGridResponse.Item> items = java.util.Arrays.stream(InterestCategory.values())
                .map(c -> new CategoryGridResponse.Item(
                        (long) (c.ordinal() + 1),
                        c.getLabel(),
                        countByCategory.getOrDefault(c.name(), 0)))
                .toList();
        return new CategoryGridResponse(items);
    }
}
