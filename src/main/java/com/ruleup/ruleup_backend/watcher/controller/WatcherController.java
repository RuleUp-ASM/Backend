package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.InvitationCreateResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherListResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherRevokeResponse;
import com.ruleup.ruleup_backend.watcher.service.WatcherInvitationService;
import com.ruleup.ruleup_backend.watcher.service.WatcherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 감시자 관리(생성자 전용) — 초대 발급 / 목록 / 해제 (§11.4).
 */
@Tag(name = "Watcher", description = "감시자 초대 · 목록 · 해제 (생성자)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/watchers")
@RequiredArgsConstructor
public class WatcherController {

    private final WatcherInvitationService invitationService;
    private final WatcherService watcherService;

    @Operation(summary = "감시자 초대 생성",
            description = "토큰+초대 링크(만료 7일)와 카톡 공유 페이로드를 반환. 룰업이 직접 보내지 않고 "
                    + "생성자가 링크를 본인 카톡으로 공유한다. 무료 3명 초과 시 WATCHER_LIMIT_EXCEEDED.")
    @PostMapping("/invitations")
    public ApiResponse<InvitationCreateResponse> createInvitation(@AuthenticationPrincipal String userId,
                                                                  @PathVariable String challengeId) {
        return ApiResponse.ok(invitationService.createInvitation(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "감시자 목록", description = "생성자 전용. 비유저 연락처는 마스킹. status=INVITED/ACTIVE/ALL(기본 ACTIVE).")
    @GetMapping
    public ApiResponse<WatcherListResponse> list(@AuthenticationPrincipal String userId,
                                                 @PathVariable String challengeId,
                                                 @RequestParam(required = false) String status) {
        return ApiResponse.ok(watcherService.list(
                UUID.fromString(userId), UUID.fromString(challengeId), status));
    }

    @Operation(summary = "감시자 해제", description = "생성자가 감시자 해제(REVOKED) + 연락처 파기. 이후 발송 중단.")
    @DeleteMapping("/{watcherId}")
    public ApiResponse<WatcherRevokeResponse> revoke(@AuthenticationPrincipal String userId,
                                                     @PathVariable String challengeId,
                                                     @PathVariable String watcherId) {
        return ApiResponse.ok(watcherService.revoke(
                UUID.fromString(userId), UUID.fromString(challengeId), UUID.fromString(watcherId)));
    }
}
