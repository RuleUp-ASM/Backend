package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.InvitationEntryResponse;
import com.ruleup.ruleup_backend.watcher.dto.WatcherAcceptResponse;
import com.ruleup.ruleup_backend.watcher.service.WatcherInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 초대 진입과 수락.
 *
 * <p>구 계약의 <b>OTP 발송·비유저 웹 동의·수신거부 3종은 폐지</b>됐다. SMS·이메일 채널과
 * 비유저 감시자 개념이 사라졌고, 동의 성립은 인앱 수락 하나로 단일화됐다.
 */
@Tag(name = "WatcherInvitation", description = "감시자 초대 진입 · 인앱 수락")
@RestController
@RequestMapping("/api/v1/watchers")
@RequiredArgsConstructor
public class WatcherInvitationController {

    private final WatcherInvitationService invitationService;

    @Operation(
            summary = "초대 카드 조회",
            description = """
                    딥링크로 열렸을 때 "누가 무엇으로 초대했는지"를 보여준다.
                    **이 호출만으로는 어떤 동의도 성립하지 않는다.**
                    """)
    @ApiErrorCodes({ErrorCode.INVITATION_INVALID, ErrorCode.INVITATION_EXPIRED})
    @GetMapping("/invitations/{token}")
    public ApiResponse<InvitationEntryResponse> entry(@PathVariable String token) {
        return ApiResponse.ok(invitationService.getByToken(token));
    }

    @Operation(
            summary = "인앱 수락",
            description = """
                    **동의가 성립하는 유일한 경로**다. 서버가 토큰 검증과 로그인 확인을 모두 마친 뒤에만
                    PENDING → ACTIVE 로 전이하고 수락 시각을 남긴다 —
                    클라이언트가 "수락했다"고 주장하는 것으로는 전이하지 않는다.

                    **로그인 필수**다. 웹 수락은 동의 주체 확인이 약해 인정하지 않으며,
                    미설치자는 스토어를 거쳐 가입한 뒤 이 경로로 들어온다.
                    """)
    @ApiErrorCodes({ErrorCode.INVITATION_INVALID, ErrorCode.INVITATION_EXPIRED,
            ErrorCode.ALREADY_WATCHER, ErrorCode.CANNOT_WATCH_SELF, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/invitations/{token}/accept")
    public ApiResponse<WatcherAcceptResponse> accept(@AuthenticationPrincipal String userId,
                                                     @PathVariable String token) {
        return ApiResponse.ok(invitationService.accept(token, UUID.fromString(userId)));
    }
}
