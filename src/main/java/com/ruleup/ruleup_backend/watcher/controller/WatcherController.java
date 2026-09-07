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
 *
 * <p><b>무료 슬롯 3자리는 표기가 아니라 실제 한도다</b>(2026-09-07). 목록이 「감시자 2/3」을
 * 그리는데 서버가 네 번째를 통과시키면 사용자가 본 사실과 서버 상태가 갈라지므로, 발급과
 * 수락 양쪽에서 센다.
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

                    **무료 슬롯은 3자리다.** 살아 있는 관계와 아직 유효한 미수락 초대를 함께 세며,
                    자리가 없으면 409 `WATCHER_LIMIT_EXCEEDED` 다. 만료된 초대는 자리를 돌려준다.
                    """)
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.NOT_CHALLENGE_OWNER,
            ErrorCode.WATCHER_LIMIT_EXCEEDED, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/invitations")
    public ApiResponse<InvitationCreateResponse> createInvitation(@AuthenticationPrincipal String userId,
                                                                  @PathVariable String challengeId) {
        return ApiResponse.ok(invitationService.createInvitation(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(
            summary = "내가 지정한 감시자 목록",
            description = """
                    슬롯 현황(`slots`)과 목록(`watchers`)을 함께 내린다.

                    `slots` 는 `{ used, freeLimit, subscribed }` 다 — **`{used, total}` 이 아니다.**
                    구독 시 `freeLimit` 이 null(무제한)이 되므로 `used/freeLimit` 을 그대로 그리면
                    「2/null」이 뜬다.

                    `status` 는 원천을 가른다. 수락된 관계는 `ACTIVE`, 아직 수락되지 않은 초대는
                    `INVITED` 다 — 초대 시점에는 누가 수락할지 모르므로 관계 행이 없고, 그래서
                    `INVITED` 줄은 `displayName` 이 null 이고 `expiresAt`(토큰 만료)이 채워진다.

                    감시자는 **룰업 앱 유저만** 가능하므로 `type` 은 항상 `USER` 이고
                    `contactMasked` 는 항상 null 이다 — 연락처를 어느 테이블에도 두지 않는다.
                    """)
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
