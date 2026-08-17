package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.InvitationDtos;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.service.ChallengeInvitationService;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Challenge Invitation", description = "초대 링크 발급(방장) · 조회 · 수락 — 비공개 방에 들어오는 유일한 경로")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/invitations/{token}")
@RequiredArgsConstructor
public class ChallengeInvitationController {

    private final ChallengeInvitationService service;

    @Operation(
            summary = "초대 링크 조회",
            description = """
                    초대 링크로 들어온 사람에게 **어떤 방인지** 보여주는 조회다. 아무것도 바꾸지 않으며 토큰도
                    소모하지 않는다 — 실제 가입은 `POST /api/v1/challenges/invitations/{token}/accept` 에서 일어난다.

                    **로그인이 필요하다.** 챌린지 멤버가 되려면 룰업 계정이 있어야 하므로, 비로그인 상태로 링크를
                    열었다면 클라이언트가 로그인부터 태운 뒤 이 API 를 호출한다.

                    `joinable` 로 **수락 버튼을 누르기 전에** 들어갈 수 있는 방인지 알 수 있다. false 면
                    `blockReason` 에 이유가 실리고, 이 값은 수락 API 의 `error.reason` 과 **같은 enum** 이다.
                    - `FULL` — 정원 마감
                    - `TIER_GATE` — 표시 티어가 방의 최소 티어에 못 미침
                    - `REJOIN_COOLDOWN` — 이 방에서 나갔거나 강퇴돼 재입장 대기 중
                    - `FREE_LIMIT` — 내가 동시에 참여할 수 있는 방 개수를 이미 채움
                    - `ALREADY_JOINED` — 이미 이 방의 멤버(수락할 필요가 없다)
                    - `CHALLENGE_COMPLETED` — 이미 종료된 방

                    `joinable:true` 를 봤다고 수락이 보장되지는 않는다. 조회와 수락 사이에 정원이 찰 수 있으므로
                    클라이언트는 수락 시점의 409 도 처리해야 한다.

                    **없는 토큰은 404, 만료·이미 사용된 토큰은 410** 이다. 만료와 사용됨을 구분하지 않는 이유는
                    클라이언트가 할 일이 "이 링크는 이제 못 쓴다"로 같고, 구분해주면 남의 초대 상태가 새기 때문이다.
                    초대한 방장이 그사이 탈퇴했으면 `inviterNickname` 만 null 이고 링크 자체는 유효하다.
                    """
    )
    @ApiErrorCodes({ErrorCode.INVITATION_NOT_FOUND, ErrorCode.INVITATION_EXPIRED,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @GetMapping
    public ApiResponse<InvitationDtos.PreviewResponse> preview(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "초대 링크에 실려 있는 토큰", required = true) @PathVariable String token) {
        return ApiResponse.ok(service.preview(UUID.fromString(userId), token));
    }

    @Operation(
            summary = "초대 수락 (= 가입)",
            description = """
                    **초대 수락은 가입 그 자체다.** 초대장이 대신해주는 것은 "비공개 방이라 직접 가입할 수 없다"는
                    검증 하나뿐이고, 나머지 게이트는 일반 가입과 똑같이 걸린다 —
                    재입장 대기 → 동시 참여 개수 → 정원 → 최소 티어 순으로 판정한다.
                    성공 응답도 `POST /api/v1/challenges/{challengeId}/members` 와 같은 스키마다.

                    거절은 전부 409 `JOIN_BLOCKED` + `error.reason` 이며, reason 값은 조회 API 의
                    `blockReason` 과 같은 enum 이다. `REJOIN_COOLDOWN` 일 때는 `error.rejoinAvailableAt` 에
                    다시 들어올 수 있는 시각이 함께 실린다.

                    **토큰은 가입에 성공했을 때만 소모된다.** 게이트에 막힌 경우에는 링크가 살아 있어서, 자리가 난
                    뒤 같은 링크로 다시 시도할 수 있다. 이미 소모된 링크는 410 `INVITATION_EXPIRED` 다.
                    같은 링크로 두 명이 동시에 수락하면 한 명만 통과하고 나머지는 410 이다.

                    응답의 `countFromCycle` 은 **판정이 시작되는 날짜**다. 사이클은 1주 고정이라 주 중간에
                    들어오면 다음 사이클 경계부터 세며, 그때까지의 날짜는 성공/실패 어느 쪽으로도 잡히지 않는다.
                    `requiredPermissions` 는 자동 인증 방일 때만 채워지고, 서버는 권한 보유를 가입 게이트로
                    검사하지 않는다 — 확보는 클라이언트 책임이다.
                    """
    )
    @ApiErrorCodes({ErrorCode.JOIN_BLOCKED, ErrorCode.INVITATION_NOT_FOUND, ErrorCode.INVITATION_EXPIRED,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/accept")
    public ApiResponse<JoinResponse> accept(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "초대 링크에 실려 있는 토큰", required = true) @PathVariable String token) {
        return ApiResponse.ok(service.accept(UUID.fromString(userId), token));
    }
}
