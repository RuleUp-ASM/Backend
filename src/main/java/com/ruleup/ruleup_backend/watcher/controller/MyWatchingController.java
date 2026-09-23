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

@Tag(name = "MyWatching", description = "내 감시 관계 조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/watching")
@RequiredArgsConstructor
public class MyWatchingController {

    private final WatcherService watcherService;

    @Operation(
            summary = "내 감시 관계 목록",
            description = "조회 전용. 푸시는 알림 설정의 마스터·챌린지 그룹으로 제어한다.")
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<MyWatchingDtos.ListResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(watcherService.listMyWatching(UUID.fromString(userId)));
    }

}
