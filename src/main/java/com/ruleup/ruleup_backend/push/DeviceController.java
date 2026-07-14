package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.push.domain.DevicePlatform;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 디바이스 FCM 토큰 등록/해제. 고스트(무음) 푸시 전송 대상 관리. */
@Tag(name = "Device", description = "FCM 디바이스 토큰 등록/해제")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceTokenService deviceTokenService;

    @Operation(summary = "FCM 토큰 등록",
            description = "로그인 유저의 디바이스 FCM 토큰을 등록(upsert). 앱 최초 획득·onNewToken 시 호출.")
    @PostMapping
    public ApiResponse<Void> register(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody RegisterRequest request) {
        deviceTokenService.register(UUID.fromString(userId), request.token(), request.platform());
        return ApiResponse.ok();
    }

    @Operation(summary = "FCM 토큰 해제",
            description = "로그아웃/토큰 폐기 시 본인 디바이스 토큰 해제.")
    @DeleteMapping
    public ApiResponse<Void> unregister(@AuthenticationPrincipal String userId,
                                        @Valid @RequestBody UnregisterRequest request) {
        deviceTokenService.unregister(UUID.fromString(userId), request.token());
        return ApiResponse.ok();
    }

    /** platform 미지정 시 서비스에서 ANDROID 로 기본 처리. */
    public record RegisterRequest(@NotBlank String token, DevicePlatform platform) {}

    public record UnregisterRequest(@NotBlank String token) {}
}
