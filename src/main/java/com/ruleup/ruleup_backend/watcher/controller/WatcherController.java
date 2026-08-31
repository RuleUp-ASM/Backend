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

/**
 * 감시자 초대·목록 (피감시자 전용).
 *
 * <p><b>해제 엔드포인트를 두지 않는다.</b> 관계 해제는 정책상 폐지됐고 루틴 종료 시 배치가
 * 자동으로 정리한다 — 경로를 남겨 두면 정책과 구현이 어긋난 채로 굳는다.
 */
@Tag(name = "Watcher", description = "감시자 초대 · 목록 (피감시자)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/watchers")
@RequiredArgsConstructor
public class WatcherController {

    private final WatcherInvitationService invitationService;
    private final WatcherService watcherService;

    @Operation(
            summary = "감시자 초대 발급",
            description = """
                    공유용 토큰과 카카오톡 카드 메타를 내려준다. **룰업은 전송하지 않는다** —
                    클라이언트가 사용자 본인 명의로 공유해야 사적 통신이 되고, 동의하지 않은
                    외부인에게 사업자가 먼저 닿지 않는다.

                    만료는 **7일**이다. 서버는 토큰의 **해시만 보관**하므로 이 응답이 원본을 보는 유일한 지점이다.

                    **인원 상한이 없다** — 구 무료 3명 한도는 폐지됐다.
                    """)
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.NOT_CHALLENGE_OWNER, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/invitations")
    public ApiResponse<InvitationCreateResponse> createInvitation(@AuthenticationPrincipal String userId,
                                                                  @PathVariable String challengeId) {
        return ApiResponse.ok(invitationService.createInvitation(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(
            summary = "내가 지정한 감시자 목록",
            description = "상태(PENDING/ACTIVE)와 수락 여부. 감시자는 **룰업 앱 유저만** 가능하므로 연락처 항목이 없다.")
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.NOT_CHALLENGE_OWNER, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<WatcherListResponse> list(@AuthenticationPrincipal String userId,
                                                 @PathVariable String challengeId) {
        return ApiResponse.ok(watcherService.listWatchers(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }
}
