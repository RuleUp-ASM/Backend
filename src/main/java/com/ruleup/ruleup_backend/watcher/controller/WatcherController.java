package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.InvitationCreateResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.service.WatcherInvitationService;
import com.ruleup.ruleup_backend.watcher.service.WatcherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Watcher", description = "감시자 초대 · 목록 (피감시자)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/watchers")
@RequiredArgsConstructor
public class WatcherController {

    private final WatcherInvitationService invitationService;
    private final WatcherService watcherService;

    @Operation(summary = "감시자 초대 발급", description = "7일 만료의 공유용 토큰과 카드 메타. 감시자 인원은 무제한이다.")
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.NOT_CHALLENGE_OWNER,
            ErrorCode.WATCHER_PENALTY_DISABLED, ErrorCode.LOGIN_REQUIRED})
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PostMapping("/invitations")
    public ApiResponse<InvitationCreateResponse> createInvitation(@AuthenticationPrincipal String userId,
                                                                  @PathVariable String challengeId) {
        return ApiResponse.ok(invitationService.createInvitation(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "내가 지정한 감시자 목록", description = "ACTIVE(기본), INVITED, ALL. 인원 제한이나 슬롯은 없다.")
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.NOT_CHALLENGE_OWNER,
            ErrorCode.INVALID_REQUEST, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<WatcherListResponse> list(@AuthenticationPrincipal String userId,
                                                 @PathVariable String challengeId,
                                                 @RequestParam(required = false) String status) {
        return ApiResponse.ok(watcherService.listWatchers(
                UUID.fromString(userId), UUID.fromString(challengeId), status));
    }
}
