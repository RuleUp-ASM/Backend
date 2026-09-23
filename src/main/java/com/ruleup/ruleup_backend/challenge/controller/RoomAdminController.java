package com.ruleup.ruleup_backend.challenge.controller;

import com.ruleup.ruleup_backend.challenge.dto.RoomAdminDtos;
import com.ruleup.ruleup_backend.challenge.service.RoomAdminService;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 방 운영 — <b>초대 발급</b>.
 *
 * <p>강퇴·방장 위임·방장 승계는 여기 있었으나 <b>페이지1 범위에서 빠졌다</b>(챌린지 정책 §7.1 · §11,
 * 2026-08-25 방장 권한 축소). 클라이언트에서 버튼만 감추는 것으로는 부족하다 — 매핑이 살아 있으면
 * 그대로 호출된다. 그래서 {@link LegacyRoomAdminController} 로 옮기고 기능 플래그로 잠갔다.
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}")
@RequiredArgsConstructor
public class RoomAdminController {

    private final RoomAdminService service;

    @Tag(name = "Challenge Invitation")
    @Operation(
            summary = "초대 링크 발급",
            description = """
                    방장이 비공개 방에 사람을 부르기 위한 1회성 링크를 만든다.

                    **비공개 그룹 방에서만 만들 수 있다.** 공개 방은 초대 없이 그냥 가입하면 되고 솔로 방은 부를
                    사람이 없으므로, 둘 다 409 `NOT_PRIVATE_CHALLENGE` 다(클라이언트는 애초에 버튼을 감춘다).

                    응답의 `token` 은 **이 응답에서만 볼 수 있다.** 서버에는 해시만 저장하므로 다시 조회할 수
                    없고, 잃어버리면 새로 발급해야 한다. `inviteUrl` 은 그 토큰을 붙인 상대 경로다.

                    유효기간은 **7일**이고 수락 한 번으로 소모된다. 여러 명을 부르려면 사람 수만큼 발급한다.
                    발급 횟수 제한은 없다.

                    받은 사람은 `GET /api/v1/challenges/invitations/{token}` 으로 방을 확인한 뒤
                    `POST .../accept` 로 들어온다.
                    """
    )
    @ApiErrorCodes({ErrorCode.NOT_CHALLENGE_OWNER, ErrorCode.NOT_PRIVATE_CHALLENGE,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoomAdminDtos.InvitationResponse> invite(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "방 id", required = true) @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.invite(UUID.fromString(userId), challengeId));
    }
}
