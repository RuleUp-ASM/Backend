package com.ruleup.ruleup_backend.recommendation.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.recommendation.dto.RecommendedRoutine;
import com.ruleup.ruleup_backend.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 발견 추천 API(④). 관심사·세그먼트 기반 루틴 추천. */
@Tag(name = "Recommendation", description = "루틴 발견 추천")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @Operation(summary = "루틴 추천", description = "관심사 + 세그먼트 인기도 기반 루틴 템플릿 추천. 진행 중 템플릿 제외. 최대 3건.")
    @GetMapping("/routines")
    public ApiResponse<List<RecommendedRoutine>> routines(@AuthenticationPrincipal String userId,
                                                          @RequestParam(defaultValue = "3") int limit) {
        return ApiResponse.ok(recommendationService.recommendRoutines(UUID.fromString(userId), limit));
    }
}
