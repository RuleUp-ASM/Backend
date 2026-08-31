package com.ruleup.ruleup_backend.applink;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱링크 유효성 검사. <b>로그인 없이</b> 부를 수 있다 — 앱 진입 시점이라 아직 토큰이 없을 수 있다. */
@Tag(name = "AppLink", description = "딥링크 유효성 검사")
@RestController
@RequestMapping("/api/v1/app-links")
@RequiredArgsConstructor
public class AppLinkController {

    private final AppLinkCheckService appLinkCheckService;

    @Operation(summary = "앱링크 유효성 검사",
            description = """
                    딥링크로 앱에 진입했을 때 **화면 라우팅 전에** 링크가 유효한지 확인한다.
                    형식 · 존재 · 만료 세 가지를 본다.

                    **유효하지 않아도 200 이다** — 링크가 나쁜 것이지 요청이 잘못된 게 아니라서,
                    사유를 `reason` 으로 내리고 클라가 안내 화면을 고른다.
                    유일한 4xx 는 `url` 자체가 없을 때뿐이다.

                    여기서 통과해도 **실제 진입 가능 여부(정원·가입 자격 등)는 각 링크 타입의 조회 API 가
                    따로 판단한다** — 이 API 는 링크 자체의 유효성만 본다.
                    """)
    @ApiErrorCodes({ErrorCode.APP_LINK_URL_REQUIRED})
    @PostMapping("/check")
    public ApiResponse<AppLinkCheckDtos.Response> check(@RequestBody AppLinkCheckDtos.Request request) {
        return ApiResponse.ok(appLinkCheckService.check(request.url()));
    }
}
