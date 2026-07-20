package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.DelegationActionRequest;
import com.ruleup.ruleup_backend.challenge.dto.DelegationActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.DelegationRequestBody;
import com.ruleup.ruleup_backend.challenge.dto.DelegationResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeDelegationService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 방장 위임 API (§7-2). 모두 로그인 필요.
 *  - 요청 생성(OWNER): 대상 MANAGER, 7일 만료, 챌린지당 유효 1건.
 *  - 응답: ACCEPT(대상자, role swap)/REJECT(대상자)/CANCEL(요청 OWNER).
 */
@Tag(name = "Challenge Delegation", description = "방장 위임")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/delegation")
@RequiredArgsConstructor
public class ChallengeDelegationController {

    private final ChallengeDelegationService delegationService;

    @Operation(summary = "방장 위임 요청", description = "OWNER만. 대상은 MANAGER. 요청은 7일 후 자동 만료. 유효 요청은 챌린지당 1건.")
    @PostMapping
    public ApiResponse<DelegationResponse> request(@AuthenticationPrincipal String userId,
                                                   @PathVariable String challengeId,
                                                   @RequestBody DelegationRequestBody body) {
        return ApiResponse.ok(delegationService.request(
                UUID.fromString(userId), UUID.fromString(challengeId), UUID.fromString(body.targetUserId())));
    }

    @Operation(summary = "방장 위임 응답",
            description = "ACCEPT(대상자, role swap)/REJECT(대상자)/CANCEL(요청 OWNER). 만료 요청 응답 시 410.")
    @PatchMapping("/{delegationId}")
    public ApiResponse<DelegationActionResponse> respond(@AuthenticationPrincipal String userId,
                                                         @PathVariable String challengeId,
                                                         @PathVariable String delegationId,
                                                         @RequestBody DelegationActionRequest request) {
        return ApiResponse.ok(delegationService.respond(
                UUID.fromString(userId), UUID.fromString(challengeId),
                UUID.fromString(delegationId), request.action()));
    }
}
