package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.InterestCategory;

import java.util.Arrays;
import java.util.List;

/**
 * GET /api/v1/categories 응답 (스펙 4.7).
 * 가입/프로필 화면에서 쓸 관심 카테고리 마스터 목록.
 * 서버는 code·label만 제공하고, 아이콘은 클라이언트가 code로 매핑한다.
 */
public record CategoryListResponse(int maxSelectable, List<Category> categories) {

    public record Category(String code, String label) {}

    /** enum 15종을 그대로 응답 형태로 변환 */
    public static CategoryListResponse of() {
        List<Category> list = Arrays.stream(InterestCategory.values())
                .map(c -> new Category(c.name(), c.getLabel()))
                .toList();
        return new CategoryListResponse(InterestCategory.MAX_SELECTABLE, list);
    }
}