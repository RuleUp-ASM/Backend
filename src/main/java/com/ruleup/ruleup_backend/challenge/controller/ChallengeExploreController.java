package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.CategoryGridResponse;
import com.ruleup.ruleup_backend.challenge.dto.ExploreResponse;
import com.ruleup.ruleup_backend.challenge.dto.TrendingResponse;
import com.ruleup.ruleup_backend.challenge.explore.CategoryCountService;
import com.ruleup.ruleup_backend.challenge.explore.ExploreQueryService;
import com.ruleup.ruleup_backend.challenge.explore.TrendingService;
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

    private final CategoryCountService categoryService;
    private final TrendingService trendingService;
    private final ExploreQueryService exploreService;

    @Operation(summary = "카테고리 그리드(홈)",
            description = "관심 분야 정책 12종 고정 순서 + 지금 들어갈 수 있는 공개 그룹 방 수. "
                    + "PUBLIC+GROUP+(UPCOMING|ACTIVE) 집계 — 시작 예정 방도 가입 가능하므로 함께 센다. "
                    + "비공개·솔로·종료된 방은 세지 않는다. 생성·상태 전환·삭제 시 즉시 갱신(캐시 백스톱 1분).")
    @GetMapping("/api/v1/challenge-categories")
    public ApiResponse<CategoryGridResponse> categories() {
        return ApiResponse.ok(categoryService.getCategories());
    }

    @Operation(summary = "실시간 인기(홈)",
            description = "최근 24시간 신규 참여 수 기준 Top 20(동점이면 최근 참여 우선). 1시간 배치 + Caffeine. "
                    + "후보는 PUBLIC+GROUP+UPCOMING/ACTIVE. 티어 필터를 적용하지 않으며 못 들어가는 방은 joinable=false 로만 표시한다. "
                    + "내가 이미 들어가 있는 방(내가 만든 방 포함)도 목록에 남으며 joined=true 로 구분한다 — "
                    + "참여 버튼은 joined 로 판단해야 한다(joinable 은 티어 잠금 표시용).")
    @GetMapping("/api/v1/challenges/trending")
    public ApiResponse<TrendingResponse> trending(@AuthenticationPrincipal String userId,
                                                  @RequestParam(required = false) String category) {
        return ApiResponse.ok(trendingService.getTrending(UUID.fromString(userId), category));
    }

    @Operation(summary = "둘러보기 목록",
            description = "① 노출 제외(비공개·솔로·종료·내가 신고한 방) → ② 필터 AND(카테고리 OR·인증방식·티어컷) "
                    + "→ ③ 정렬 6종 단일 적용. 정원 마감 방은 빼지 않고 isFull 로 구분한다. "
                    + "내가 이미 들어가 있는 방도 빼지 않고 joined 로 구분한다(참여 버튼 판단은 joined 로). "
                    + "커서 페이징 기본 10·최대 20이며 totalCount 는 제공하지 않는다(무한 스크롤).")
    @GetMapping("/api/v1/challenges/explore")
    public ApiResponse<ExploreResponse> explore(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) String verifyType,
            @RequestParam(required = false, defaultValue = "false") Boolean eligibleOnly,
            @RequestParam(required = false, defaultValue = "POPULAR") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(exploreService.explore(
                UUID.fromString(userId), categories, verifyType, eligibleOnly, sort, cursor, size));
    }
}
