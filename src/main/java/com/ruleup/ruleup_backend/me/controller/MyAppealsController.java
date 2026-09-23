package com.ruleup.ruleup_backend.me.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.me.dto.MyAppealsResponse;
import com.ruleup.ruleup_backend.me.service.MyAppealsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 이의 제기 현황 — 마이페이지 5-2 #7. 접수는 인증 모듈 소관이고 여기서는 이력만 읽는다. */
@Tag(name = "Me", description = "마이 홈 · 캘린더 · 통계 · 티어 · 이의")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class MyAppealsController {

    private final MyAppealsService appealsService;

    @Operation(summary = "내가 한 이의제기 조회",
            description = """
                    내가 낸 이의 이력(최신순).

                    **횟수 한도가 없어 잔여 구제권이라는 개념이 없다.** 접수된 건은 즉시 인용되므로
                    계류·기각 상태가 존재하지 않고, 형식 미달(사유 10자 미만)은 접수 자체가 되지 않아
                    이력에도 남지 않는다 — 그래서 이 목록은 전건이 `ACCEPTED` 다.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/api/v1/users/me/appeals")
    public ApiResponse<MyAppealsResponse> myAppeals(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(appealsService.history(UUID.fromString(userId)));
    }
}
