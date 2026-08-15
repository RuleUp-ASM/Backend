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
@Tag(name = "Category", description = "관심 카테고리 마스터 — 가입·프로필 화면의 선택지")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    @Operation(
            summary = "관심 카테고리 목록 조회",
            description = """
                    가입(온보딩)과 프로필 수정 화면에서 쓰는 관심 카테고리 선택지를 내려준다.
                    응답의 `code` 를 그대로 `POST /api/v1/auth/signup` 의 `interestCategories` 에 담아 보내면 된다.

                    선택 상한도 `maxSelectable` 로 함께 내려간다. **클라이언트가 개수를 하드코딩하지 않는다** —
                    정책이 바뀌면 서버 값만 바뀌어야 한다. 관심사는 건너뛸 수 있어 0개(빈 배열)도 유효하다.

                    아이콘·색상은 서버가 주지 않는다. 클라이언트가 `code` 로 매핑한다.

                    거의 변하지 않는 마스터 데이터라 서버에서 캐시하며, 로그인 전에도 호출할 수 있는 공개 API 다.
                    """
    )
    @GetMapping
    @Cacheable("categories")     // 매번 새로 만들 필요 없는 고정 목록 → 첫 호출 결과를 재사용
    public ApiResponse<CategoryListResponse> getCategories() {
        return ApiResponse.ok(CategoryListResponse.of());
    }
}
