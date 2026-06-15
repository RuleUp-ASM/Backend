package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 알림 API. 모두 로그인 필요. */
@Tag(name = "Notification", description = "내 알림 조회 · 읽음 처리")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록", description = "최신순. 닉네임/사진 변경 요청 등.")
    @GetMapping
    public ApiResponse<NotificationResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(notificationService.list(UUID.fromString(userId)));
    }

    @Operation(summary = "알림 읽음 처리")
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal String userId,
                                      @PathVariable String notificationId) {
        notificationService.markRead(UUID.fromString(userId), UUID.fromString(notificationId));
        return ApiResponse.ok();
    }
}
