package com.ruleup.ruleup_backend.onboarding;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
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

    @Operation(
            summary = "온보딩 정보 입력",
            description = """
                    가입 후 최초 접속 시 추천 품질을 높이기 위한 선택 정보를 수집한다.
                    가입 자체는 `POST /api/v1/auth/signup` 에서 이미 끝났으므로, **이 API 는 호출하지 않아도 서비스 이용에 지장이 없다**
                    (미입력이면 전체 인기도 기반으로 추천한다).

                    **보낸 필드만 반영하는 부분 갱신**이다. 건너뛰고 싶은 항목은 필드를 빼거나 null 로 두면 기존 값이 유지된다.
                    빈 문자열도 미전송과 같게 취급한다.

                    - `gender` — 보내면 갱신된다. 값이 정의된 성별이 아니면 400.
                    - `birthDate` — **이미 생일이 저장돼 있으면 무시된다.** 생일은 가입 시 확정되고 이후 수정할 수 없다는 계약이라,
                      값을 보내도 조용히 무시하고 200 으로 응답한다(에러가 아니다). 값이 없던 계정에만 채워진다.
                      형식은 `YYYY-MM-DD` 이고 미래 날짜는 400 이다.

                    국가 코드는 받지 않는다. 사용자 입력이 아니라 서버가 로그인·가입 요청 정보에서 직접 해석한다.

                    응답 본문은 없다 — `{"success": true, "data": null, "error": null}`.
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.LOGIN_REQUIRED,
            ErrorCode.ACCOUNT_LOCKED, ErrorCode.ACCOUNT_BANNED})
    @PutMapping("/api/v1/onboarding/me")
    public ApiResponse<Void> onboarding(@AuthenticationPrincipal String userId,
                                        @RequestBody DemographicsRequest request) {
        demographicsService.update(UUID.fromString(userId), request);
        return ApiResponse.ok();
    }
}
