package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherReactionDtos;
import com.ruleup.ruleup_backend.watcher.service.WatcherReactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 실패 통지에 대한 응원·놀림. */
@Tag(name = "WatcherNotice", description = "감시자 응원 · 놀림")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/watcher-notices/{noticeId}")
@RequiredArgsConstructor
public class WatcherNoticeController {

    private final WatcherReactionService reactionService;

    @Operation(
            summary = "응원·놀림 전송",
            description = """
                    받은 실패 통지에 `CHEER` 또는 `TEASE` 를 보낸다.

                    **실패 건당 1회**이며 `(noticeId, watcherUserId)` 복합 PK 로 보장한다 —
                    서버 카운터로 풀면 경합에서 초과가 나온다.
                    **응원과 놀림을 둘 다 보낼 수 없다** — 하나를 보내면 그 통지에 대한 반응은 끝난다.

                    성공하면 **실패 당사자 1명에게만** 알림이 가며 반응한 감시자의 닉네임이 공개된다.
                    """)
    @ApiErrorCodes({ErrorCode.REACTION_ALREADY_SENT, ErrorCode.NOT_WATCHER,
            ErrorCode.NOTICE_NOT_FOUND, ErrorCode.INVALID_REQUEST, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/reactions")
    public ApiResponse<WatcherReactionDtos.Response> react(@AuthenticationPrincipal String userId,
                                                           @PathVariable String noticeId,
                                                           @RequestBody WatcherReactionDtos.Request request) {
        return ApiResponse.ok(reactionService.react(
                UUID.fromString(userId), UUID.fromString(noticeId), request));
    }
}
