package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.CategoryListResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관심 카테고리 마스터 조회 (스펙 4.7).
 * 값이 거의 변하지 않는 "정적 마스터 데이터"라 캐시 대상이다.
 */
@Tag(name = "Category", description = "관심 카테고리 마스터")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    @Operation(summary = "관심 카테고리 목록 조회", description = "가입/프로필 화면용 카테고리 12종")
    @GetMapping
    @Cacheable("categories")     // 매번 새로 만들 필요 없는 고정 목록 → 첫 호출 결과를 재사용
    public ApiResponse<CategoryListResponse> getCategories() {
        return ApiResponse.ok(CategoryListResponse.of());
    }
}