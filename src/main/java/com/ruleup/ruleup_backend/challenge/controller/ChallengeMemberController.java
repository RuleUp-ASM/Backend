package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.LeaveResponse;
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
 * 챌린지 멤버십 API (생성 및 라이프사이클 §5·§7). 모두 로그인 필요.
 *  - 가입(§5): 승인 절차 없이 검증 통과 시 즉시 ACTIVE.
 *  - 멤버 목록(§7): 현재 멤버만. 익명 챌린지는 닉네임 마스킹.
 */
@Tag(name = "Challenge Member", description = "챌린지 가입 · 멤버 목록")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/members")
@RequiredArgsConstructor
public class ChallengeMemberController {

    private final ChallengeMemberService memberService;

    @Operation(summary = "챌린지 가입",
            description = "승인 절차 없이 게이트 통과 시 즉시 ACTIVE. 종료→비공개(초대로만)→재입장 대기·차단→"
                    + "동시 3개(사용자 행 락)→정원(챌린지 행 락)→최소 티어 순 판정. "
                    + "거절은 전부 409 JOIN_BLOCKED + reason. 기기 권한은 서버가 검사하지 않는다 — "
                    + "클라가 공개 상세의 requiredPermissions로 가입 전에 확보한다.")
    @PostMapping
    public ApiResponse<JoinResponse> join(@AuthenticationPrincipal String userId,
                                          @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.join(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "챌린지 멤버 목록", description = "현재 멤버만 반환(승인제 폐기). 익명 챌린지는 닉네임 마스킹.")
    @GetMapping
    public ApiResponse<MemberListResponse> listMembers(@AuthenticationPrincipal String userId,
                                                       @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.listMembers(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(summary = "챌린지 탈퇴",
            description = "본인 탈퇴 — 방장도 자유롭게 나갈 수 있고, 넘기지 않고 나가면 즉시 봇방장 체제로 전환된다. "
                    + "감점 면제는 1년 이상 성공(LONG_SUCCESS)·선착순 승계 3일 면책(SUCCESSION_GRACE). 재입장은 1주 대기.")
    @DeleteMapping("/me")
    public ApiResponse<LeaveResponse> leave(@AuthenticationPrincipal String userId,
                                            @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.leave(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

}
