package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.MyWatchingDtos;
import com.ruleup.ruleup_backend.watcher.service.WatcherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 마이페이지 패널티 수신 관리 — 감시자 본인 화면.
 *
 * <p>조회와 토글 둘뿐이다. <b>해제 엔드포인트를 두지 않는다</b> — 관계는 루틴 종료 시 자동
 * 제거되고, 지금 통지를 멈추려면 토글을 끄면 된다.
 */
@Tag(name = "MyWatching", description = "내가 감시자로 등록된 관계 조회 · 수신 토글")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/watching")
@RequiredArgsConstructor
public class MyWatchingController {

    private final WatcherService watcherService;

    @Operation(
            summary = "내 감시 관계 목록",
            description = "**조회 전용**이다. 관계를 끊는 경로는 없으며 수신은 토글로 닫는다.")
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<MyWatchingDtos.ListResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(watcherService.listMyWatching(UUID.fromString(userId)));
    }

    @Operation(
            summary = "수신 토글",
            description = """
                    푸시 수신만 끈다. **감시자 해제가 아니다** — 관계는 그대로 살아 있고
                    **알림함 적재도 유지**된다. 언제든 다시 켤 수 있다.

                    OFF 시각은 `watcher_consent_logs` 에 남는다 — "언제부터 받지 않겠다고 했는지"가
                    분쟁의 근거이기 때문이다.

                    구 계약의 `revoke` 는 해제 개념과 함께 폐지됐다.
                    """)
    @ApiErrorCodes({ErrorCode.WATCHER_NOT_FOUND, ErrorCode.INVALID_REQUEST, ErrorCode.LOGIN_REQUIRED})
    @PatchMapping("/{watcherId}")
    public ApiResponse<MyWatchingDtos.PatchResponse> toggle(@AuthenticationPrincipal String userId,
                                                            @PathVariable String watcherId,
                                                            @RequestBody MyWatchingDtos.PatchRequest request) {
        return ApiResponse.ok(watcherService.togglePush(
                UUID.fromString(userId), UUID.fromString(watcherId), request));
    }
}
