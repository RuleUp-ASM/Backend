package com.ruleup.ruleup_backend.auth.dto;

import com.ruleup.ruleup_backend.user.domain.InterestCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.List;

/**
 * GET /api/v1/categories 응답 (스펙 4.7).
 * 가입/프로필 화면에서 쓸 관심 카테고리 마스터 목록.
 * 서버는 code·label만 제공하고, 아이콘은 클라이언트가 code로 매핑한다.
 */
@Schema(name = "CategoryListResponse", description = """
        관심 카테고리 마스터. 가입·프로필 화면의 선택지를 그대로 그리는 데 쓴다.
        아이콘은 서버가 주지 않고 클라이언트가 code 로 매핑한다.""")
public record CategoryListResponse(

        @Schema(description = "최대 선택 가능 개수. 클라이언트는 이 값으로 선택 상한 UI 를 그린다(하드코딩 금지).",
                example = "6", requiredMode = Schema.RequiredMode.REQUIRED)
        int maxSelectable,

        @Schema(description = "카테고리 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Category> categories) {

    @Schema(name = "Category", description = "관심 카테고리 1건")
    public record Category(
            @Schema(description = "서버에 제출할 코드 값", example = "EXERCISE",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String code,

            @Schema(description = "화면에 표시할 한글 이름", example = "운동",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String label) {}

    /** enum 전체를 그대로 응답 형태로 변환 */
    public static CategoryListResponse of() {
        List<Category> list = Arrays.stream(InterestCategory.values())
                .map(c -> new Category(c.name(), c.getLabel()))
                .toList();
        return new CategoryListResponse(InterestCategory.MAX_SELECTABLE, list);
    }
}
