package com.ruleup.ruleup_backend.watcher.controller;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.watcher.dto.*;
import com.ruleup.ruleup_backend.watcher.service.WatcherInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 감시자 초대 진입·수락·동의(OTP)·수신거부 (§11.4).
 * 진입(GET)·OTP·동의·수신거부는 공개(비유저 접근). 수락(accept)만 유저 로그인 필요.
 */
@Tag(name = "WatcherInvitation", description = "감시자 초대 진입 · 수락 · 동의(OTP) · 수신거부")
@RestController
@RequestMapping("/api/v1/watchers")
@RequiredArgsConstructor
public class WatcherInvitationController {

    private final WatcherInvitationService invitationService;

    @Operation(summary = "초대 진입(공개)",
            description = "토큰 검증 + 안내. viewerIsUser로 인앱 수락(유저)/웹 동의 폼(비유저) 분기. "
                    + "30일 차단이면 200 + blocked=true.")
    @GetMapping("/invitations/{token}")
    public ApiResponse<InvitationEntryResponse> entry(@AuthenticationPrincipal String userId,
                                                      @PathVariable String token) {
        UUID viewer = (userId != null) ? UUID.fromString(userId) : null;
        return ApiResponse.ok(invitationService.getByToken(token, viewer));
    }

    @Operation(summary = "유저 수락(인앱=동의)", description = "룰업 유저 감시자의 인앱 수락. 채널 IN_APP. 로그인 필요.")
    @PostMapping("/invitations/{token}/accept")
    public ApiResponse<WatcherAcceptResponse> accept(@AuthenticationPrincipal String userId,
                                                     @PathVariable String token) {
        return ApiResponse.ok(invitationService.accept(token, UUID.fromString(userId)));
    }

    @Operation(summary = "비유저 OTP 발송(공개)",
            description = "감시자 본인이 입력한 번호로 SMS OTP 발송(야간 디퍼 예외=즉시). 재발송 쿨다운 적용.")
    @PostMapping("/invitations/{token}/otp")
    public ApiResponse<OtpSendResponse> sendOtp(@PathVariable String token,
                                                @RequestBody OtpSendRequest request) {
        return ApiResponse.ok(invitationService.sendOtp(token, request.phone()));
    }

    @Operation(summary = "비유저 동의(공개)",
            description = "OTP 검증 + 연락처·수신동의 저장. 채널 SMS, 생성자에겐 마스킹 번호만 노출.")
    @PostMapping("/invitations/{token}/consent")
    public ApiResponse<WatcherConsentResponse> consent(@PathVariable String token,
                                                       @RequestBody ConsentRequest request) {
        UUID otpId = parseOtpId(request.otpId());
        return ApiResponse.ok(invitationService.consent(token, otpId, request.otpCode(), request.consent()));
    }

    @Operation(summary = "SMS 수신거부(공개)",
            description = "SMS 본문 링크의 서명 토큰으로 처리. 즉시 REVOKED + 발송 중단 + 동일 생성자 30일 재초대 차단.")
    @PostMapping("/unsubscribe")
    public ApiResponse<WatcherUnsubscribeResponse> unsubscribe(@RequestParam String token) {
        return ApiResponse.ok(invitationService.unsubscribe(token));
    }

    private UUID parseOtpId(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessException(ErrorCode.OTP_INVALID);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.OTP_INVALID);
        }
    }
}
