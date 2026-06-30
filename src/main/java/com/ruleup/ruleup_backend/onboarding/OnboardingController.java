package com.ruleup.ruleup_backend.onboarding;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.recommendation.dto.DemographicsRequest;
import com.ruleup.ruleup_backend.recommendation.service.DemographicsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 온보딩 API — 계약: {@code PUT /api/v1/onboarding/me}.
 * 가입 후 최초 접속 시 선택 정보(birthDate·gender) 수집. 미전송/null이면 건너뛴다.
 * 국가 코드는 받지 않는다(서버가 가입·로그인 요청에서 해석). data 없음(success:true, data:null).
 * 인구통계 처리는 추천 도메인의 {@link DemographicsService}에 위임(세그먼트 추천에 사용).
 */
@Tag(name = "Onboarding", description = "가입 후 온보딩 정보 수집")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final DemographicsService demographicsService;

    @Operation(summary = "온보딩 정보 입력",
            description = "birthDate·gender(선택). 원치 않는 필드는 null/미전송으로 두면 건너뛴다. "
                    + "국가 코드는 받지 않는다. 미입력 시에도 전체 인기도 기반으로 추천된다.")
    @PutMapping("/api/v1/onboarding/me")
    public ApiResponse<Void> onboarding(@AuthenticationPrincipal String userId,
                                        @RequestBody DemographicsRequest request) {
        demographicsService.update(UUID.fromString(userId), request);
        return ApiResponse.ok();
    }
}
