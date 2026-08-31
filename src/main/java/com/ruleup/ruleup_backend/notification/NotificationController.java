package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationResponse;
import com.ruleup.ruleup_backend.notification.dto.NotificationSettingDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;

    @io.swagger.v3.oas.annotations.Operation(
            summary = "알림함 목록",
            description = """
                    커서 페이징. 보관 **6개월**을 넘긴 건은 응답에 없다.

                    커서는 `createdAt(epochMilli)|id` 복합값이다 — 00시 판정 피크에는 같은 밀리초에
                    여러 건이 적재되므로 단일 id 커서로는 페이지 경계 항목이 빠지거나 겹친다.

                    분류(`category`)는 A 필수 · B 기능 · C 마케팅이다. **모든 알림은 푸시 발송 여부와
                    무관하게 여기 적재**되며, 필수(A) 알림의 법적 고지는 이 적재 시점에 성립한다.

                    ⚠️ **잠금 계정도 열람할 수 있다** — 제재 고지가 여기 쌓이기 때문이다.
                    """)
    @GetMapping("/api/v1/notifications")
    public ApiResponse<NotificationResponse> list(@AuthenticationPrincipal String userId,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(UUID.fromString(userId), cursor, size));
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    public ApiResponse<NotificationResponse.ReadResponse> read(@AuthenticationPrincipal String userId,
                                                               @PathVariable UUID notificationId) {
        return ApiResponse.ok(service.markRead(UUID.fromString(userId), notificationId));
    }

    /** 구 앱 PATCH 호출도 같은 멱등 동작으로 잠시 호환한다. */
    @PatchMapping("/api/v1/notifications/{notificationId}/read")
    public ApiResponse<NotificationResponse.ReadResponse> readLegacy(@AuthenticationPrincipal String userId,
                                                                     @PathVariable UUID notificationId) {
        return ApiResponse.ok(service.markRead(UUID.fromString(userId), notificationId));
    }

    @PostMapping("/api/v1/notifications/read-all")
    public ApiResponse<NotificationResponse.ReadAllResponse> readAll(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.markAllRead(UUID.fromString(userId)));
    }

    @DeleteMapping("/api/v1/notifications/{notificationId}")
    public ApiResponse<NotificationResponse.DeleteResponse> delete(@AuthenticationPrincipal String userId,
                                                                   @PathVariable UUID notificationId) {
        return ApiResponse.ok(service.delete(UUID.fromString(userId), notificationId));
    }

    @GetMapping("/api/v1/users/me/notification-settings")
    public ApiResponse<NotificationSettingDtos.Response> settings(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.settings(UUID.fromString(userId)));
    }

    @PatchMapping("/api/v1/users/me/notification-settings")
    public ApiResponse<NotificationSettingDtos.Response> patchSettings(@AuthenticationPrincipal String userId,
                                                                       @RequestBody NotificationSettingDtos.PatchRequest request) {
        return ApiResponse.ok(service.patchSettings(UUID.fromString(userId), request));
    }
}
