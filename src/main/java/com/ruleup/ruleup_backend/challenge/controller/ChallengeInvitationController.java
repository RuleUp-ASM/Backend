package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.InvitationDtos;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeInvitationService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 초대 링크로 들어온 사람이 쓰는 두 엔드포인트(조회 · 수락).
 * 발급은 방장 전용이라 {@link RoomAdminController} 에 있다.
 */
@RestController
@RequestMapping("/api/v1/challenges/invitations/{token}")
@RequiredArgsConstructor
public class ChallengeInvitationController {

    private final ChallengeInvitationService service;

    @GetMapping
    public ApiResponse<InvitationDtos.PreviewResponse> preview(@AuthenticationPrincipal String userId,
                                                               @PathVariable String token) {
        return ApiResponse.ok(service.preview(UUID.fromString(userId), token));
    }

    @PostMapping("/accept")
    public ApiResponse<JoinResponse> accept(@AuthenticationPrincipal String userId,
                                            @PathVariable String token) {
        return ApiResponse.ok(service.accept(UUID.fromString(userId), token));
    }
}
