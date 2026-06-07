package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberActionRequest;
import com.ruleup.ruleup_backend.challenge.dto.MemberActionResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 챌린지 멤버십 API (스펙 3.6 ~ 3.8). 모두 로그인 필요.
 *  - 참여(3.6): 그룹+기준이면 매너 검증 후 PENDING, 솔로/기준 미설정이면 즉시 ACTIVE.
 *  - 승인·거절(3.7): OWNER만.
 *  - 멤버 목록(3.8): 기본 ACTIVE. PENDING·ALL은 OWNER만.
 */
@Tag(name = "Challenge Member", description = "챌린지 참여 · 승인/거절 · 멤버 목록")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/members")
@RequiredArgsConstructor
public class ChallengeMemberController {

    private final ChallengeMemberService memberService;

    @Operation(summary = "챌린지 참여 신청",
            description = "그룹+기준이면 매너 온도 서버 검증 후 PENDING, 솔로/기준 미설정이면 즉시 ACTIVE.")
    @PostMapping
    public ApiResponse<JoinResponse> join(@AuthenticationPrincipal String userId,
                                          @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.join(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "참여 승인/거절", description = "OWNER만. action=APPROVE(→ACTIVE, count+1) / REJECT(→REMOVED).")
    @PatchMapping("/{targetUserId}")
    public ApiResponse<MemberActionResponse> handleApplication(@AuthenticationPrincipal String userId,
                                                               @PathVariable String challengeId,
                                                               @PathVariable String targetUserId,
                                                               @RequestBody MemberActionRequest request) {
        return ApiResponse.ok(memberService.handleApplication(
                UUID.fromString(userId), UUID.fromString(challengeId),
                UUID.fromString(targetUserId), request.action()));
    }

    @Operation(summary = "챌린지 멤버 목록", description = "기본 ACTIVE. status=PENDING/ALL은 OWNER만 조회 가능. 익명 챌린지는 닉네임 마스킹.")
    @GetMapping
    public ApiResponse<MemberListResponse> listMembers(@AuthenticationPrincipal String userId,
                                                       @PathVariable String challengeId,
                                                       @RequestParam(required = false) String status) {
        return ApiResponse.ok(memberService.listMembers(
                UUID.fromString(userId), UUID.fromString(challengeId), status));
    }
}