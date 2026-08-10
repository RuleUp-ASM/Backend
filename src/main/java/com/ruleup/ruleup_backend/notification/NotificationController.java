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

    @GetMapping("/api/v1/notifications")
    public ApiResponse<NotificationResponse> list(@AuthenticationPrincipal String userId,
                                                  @RequestParam(defaultValue = "ALL") String filter,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(UUID.fromString(userId), filter, cursor, size));
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
