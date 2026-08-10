package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.RoomAdminDtos;
import com.ruleup.ruleup_backend.challenge.service.RoomAdminService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/challenges/{challengeId}")
@RequiredArgsConstructor
public class RoomAdminController {
    private final RoomAdminService service;

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoomAdminDtos.InvitationResponse> invite(@AuthenticationPrincipal String userId,
                                                               @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.invite(UUID.fromString(userId), challengeId));
    }

    @DeleteMapping("/members/{targetUserId}")
    public ApiResponse<RoomAdminDtos.KickResponse> kick(@AuthenticationPrincipal String userId,
                                                       @PathVariable UUID challengeId,
                                                       @PathVariable UUID targetUserId,
                                                       @RequestBody RoomAdminDtos.KickRequest request) {
        return ApiResponse.ok(service.kick(UUID.fromString(userId), challengeId, targetUserId, request.reason()));
    }

    @PatchMapping("/owner")
    public ApiResponse<RoomAdminDtos.TransferResponse> transfer(@AuthenticationPrincipal String userId,
                                                                @PathVariable UUID challengeId,
                                                                @RequestBody RoomAdminDtos.TransferRequest request) {
        return ApiResponse.ok(service.transfer(UUID.fromString(userId), challengeId,
                UUID.fromString(request.targetUserId())));
    }

    @PostMapping("/owner/claim")
    public ApiResponse<RoomAdminDtos.ClaimResponse> claim(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.claim(UUID.fromString(userId), challengeId));
    }
}
