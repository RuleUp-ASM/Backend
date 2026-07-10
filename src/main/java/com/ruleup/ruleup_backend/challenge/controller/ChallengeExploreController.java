package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeCategoryService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeExploreService;
import com.ruleup.ruleup_backend.challenge.service.TrendingService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 챌린지 탐색(search 스펙): 홈 카테고리 그리드 · 실시간 인기 · 둘러보기 목록.
 * 키워드 검색은 제공하지 않는다(탐색 = 인기 + 카테고리 + 필터 + 정렬).
 */
@Tag(name = "Challenge Explore", description = "챌린지 탐색 — 카테고리 · 실시간 인기 · 둘러보기 목록")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ChallengeExploreController {

    private final ChallengeCategoryService categoryService;
    private final TrendingService trendingService;
    private final ChallengeExploreService exploreService;

    @Operation(summary = "카테고리 그리드(홈)", description = "카테고리 정적 목록 + 진행 중(now<endAt) 챌린지 수. Caffeine 캐시(10분).")
    @GetMapping("/api/v1/challenge-categories")
    public ApiResponse<CategoryGridResponse> categories() {
        return ApiResponse.ok(categoryService.getCategories());
    }

    @Operation(summary = "실시간 인기(홈)", description = "최근 24h 참여의 지수감쇠 합(반감기 6h) 기준 Top 20. 10분 배치 캐시(최대 10분 지연).")
    @GetMapping("/api/v1/challenges/trending")
    public ApiResponse<TrendingResponse> trending(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(trendingService.getTrending());
    }

    @Operation(summary = "둘러보기 목록", description = "전체 공개 챌린지 대상. 필터(AND) + 정렬 7종 + 커서 페이지네이션. "
            + "종료(now≥endAt)·삭제 제외. joinableOnly 기본 true(내 매너 온도 기준 서버 계산).")
    @GetMapping("/api/v1/challenges/explore")
    public ApiResponse<ExploreResponse> explore(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String participationType,
            @RequestParam(required = false) String verificationType,
            @RequestParam(required = false, defaultValue = "true") Boolean joinableOnly,
            @RequestParam(required = false, defaultValue = "TRENDING") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "5") Integer size) {
        return ApiResponse.ok(exploreService.explore(
                UUID.fromString(userId), category, participationType, verificationType,
                joinableOnly, sort, cursor, size));
    }
}
