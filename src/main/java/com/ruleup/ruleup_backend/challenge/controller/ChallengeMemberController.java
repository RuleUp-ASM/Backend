package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.dto.LeaveResponse;
import com.ruleup.ruleup_backend.challenge.dto.MemberListResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@Tag(name = "Challenge Member", description = "챌린지 가입 · 멤버 목록 · 탈퇴 — 방 안 API 를 쓰려면 먼저 여기를 통과해야 한다")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}/members")
@RequiredArgsConstructor
public class ChallengeMemberController {

    private final ChallengeMemberService memberService;

    @Operation(
            summary = "챌린지 가입",
            description = """
                    **승인 절차가 없다.** 게이트를 통과하면 그 자리에서 ACTIVE 멤버가 되고, 방장이 승인할 때까지
                    기다리는 상태는 존재하지 않는다.

                    판정 순서는 아래와 같고, 거절은 원인을 가리지 않고 **전부 409 `JOIN_BLOCKED` +
                    `error.reason`** 이다(구 명세의 403 TIER_NOT_ELIGIBLE · 409 CHALLENGE_FULL 분리는 폐기).
                    1. `CHALLENGE_COMPLETED` — 종료된 방
                    2. `ALREADY_JOINED` — 이미 이 방의 멤버
                    3. `PRIVATE_INVITE_ONLY` — 비공개 방. **초대 링크로만** 들어올 수 있다
                       (`POST /api/v1/challenges/invitations/{token}/accept`)
                    4. `REJOIN_COOLDOWN` — 나갔거나 강퇴돼 대기 중. `error.rejoinAvailableAt` 에 가능 시각이 실린다
                    5. `FREE_LIMIT` — 동시에 참여할 수 있는 방 개수 초과
                    6. `FULL` — 정원 마감
                    7. `TIER_GATE` — 표시 티어가 방의 최소 티어 미만

                    **기기 권한은 서버가 검사하지 않는다.** 응답의 `requiredPermissions`(자동 인증 방에서만 채워짐)를
                    보고 클라이언트가 가입 전에 확보한다 — 권한이 없는 채로 가입되면 첫날부터 실패가 쌓인다.

                    `countFromCycle` 은 판정이 시작되는 날짜다. 사이클은 1주 고정이라 주 중간에 들어오면 다음
                    사이클 경계부터 세며, 그 전 날짜는 성공·실패 어느 쪽으로도 잡히지 않는다.
                    솔로 방은 본인 외에는 존재 자체를 숨기므로 404 다.
                    """
    )
    @ApiErrorCodes({ErrorCode.JOIN_BLOCKED, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping
    public ApiResponse<JoinResponse> join(@AuthenticationPrincipal String userId,
                                          @Parameter(description = "방 id", required = true)
                                          @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.join(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(
            summary = "챌린지 멤버 목록",
            description = """
                    **현재 멤버(ACTIVE)만** 반환한다. 탈퇴·강퇴된 사람은 목록에서 사라지며, 승인 대기 같은 중간
                    상태는 존재하지 않는다(승인제 폐기).

                    `role` 은 `OWNER` 또는 `MEMBER` 둘뿐이다 — 공동 관리자(MANAGER)는 폐기됐다.
                    응답 최상단의 `ownerType` 이 `BOT` 이면 방장 자리가 비어 있다는 뜻이라 목록에 OWNER 가 없다.

                    `blocked=true` 는 **내가 차단한 사람**이라는 표시다. 목록에서 빠지지는 않는다.
                    익명 챌린지는 차단과 무관하게 전원 닉네임이 마스킹되고 프로필 사진이 내려가지 않는다.

                    ACTIVE 멤버만 조회할 수 있다(403 `NOT_CHALLENGE_MEMBER`).
                    """
    )
    @ApiErrorCodes({ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<MemberListResponse> listMembers(@AuthenticationPrincipal String userId,
                                                       @Parameter(description = "방 id", required = true)
                                                       @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.listMembers(
                UUID.fromString(userId), UUID.fromString(challengeId)));
    }

    @Operation(
            summary = "챌린지 탈퇴",
            description = """
                    본인이 방에서 나간다. **방장도 자유롭게 나갈 수 있다** — 구 명세의 "방장 탈퇴 불가"·"탈퇴 시
                    재참여 영구 불가"는 둘 다 폐기됐다.

                    방장이 권한을 넘기지 않고 나가면 방은 **즉시 봇방장 체제**가 되고(`botOwnerActivated:true`)
                    남은 멤버 전원에게 "방장 자리가 비었어요" 알림이 나간다. 그때부터 누구나
                    `POST /api/v1/challenges/{challengeId}/owner/claim` 으로 방장이 될 수 있다.

                    중도 이탈에는 감점이 붙지만 두 경우는 면제된다(`exemptReason`).
                    - `LONG_SUCCESS` — 1년 이상 성공을 이어온 경우
                    - `SUCCESSION_GRACE` — 방장 승계 직후 3일 면책 기간 중인 경우(잔류 멤버 전원에게 적용)

                    재입장은 **1주 대기**다(`rejoinAvailableAt`). 강퇴와 달리 배수로 늘어나지 않는다.
                    종료된 방은 탈퇴 개념이 없어 거절된다.
                    """
    )
    @ApiErrorCodes({ErrorCode.MEMBER_NOT_FOUND, ErrorCode.CHALLENGE_COMPLETED,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/me")
    public ApiResponse<LeaveResponse> leave(@AuthenticationPrincipal String userId,
                                            @Parameter(description = "방 id", required = true)
                                            @PathVariable String challengeId) {
        return ApiResponse.ok(memberService.leave(UUID.fromString(userId), UUID.fromString(challengeId)));
    }

}
