package com.ruleup.ruleup_backend.recommendation.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.recommendation.dto.DemographicsRequest;
import com.ruleup.ruleup_backend.recommendation.dto.RecommendedRoutine;
import com.ruleup.ruleup_backend.recommendation.service.DemographicsService;
import com.ruleup.ruleup_backend.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final DemographicsService demographicsService;

    @Operation(summary = "루틴 추천", description = "관심사 + 세그먼트 인기도 기반 루틴 템플릿 추천. 진행 중 템플릿 제외.")
    @GetMapping("/routines")
    public ApiResponse<List<RecommendedRoutine>> routines(@AuthenticationPrincipal String userId,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(recommendationService.recommendRoutines(UUID.fromString(userId), limit));
    }

    @Operation(summary = "추천용 인구통계 입력",
            description = "가입 후 최초 접속 시 수집(선택: birthDate·gender). 원치 않는 필드는 null/미전송으로 두면 건너뛴다. "
                    + "국가 코드는 받지 않는다(서버가 가입·로그인 시 요청에서 해석). "
                    + "미입력 시에도 전체 인기도 기반으로 추천된다.")
    @PutMapping("/demographics")
    public ApiResponse<Void> demographics(@AuthenticationPrincipal String userId,
                                          @RequestBody DemographicsRequest request) {
        demographicsService.update(UUID.fromString(userId), request);
        return ApiResponse.ok();
    }
}
