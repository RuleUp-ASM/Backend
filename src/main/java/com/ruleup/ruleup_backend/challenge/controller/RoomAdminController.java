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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 방 운영 — 초대 발급 · 강퇴 · 방장 권한.
 *
 * <p>태그를 클래스가 아니라 메서드에 다는 이유: 초대 발급은 받는 쪽 API 2개와 한 흐름으로 읽혀야 하고
 * (Challenge Invitation), 나머지는 방장 전용 운영(Challenge Admin)이라 문서상 묶이는 곳이 다르다.
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

    @Tag(name = "Challenge Admin")
    @Operation(
            summary = "멤버 강퇴",
            description = """
                    방장이 멤버 한 명을 방에서 내보낸다. **사유는 10~500자 필수**다.

                    사유를 강제하는 이유는 앱 안에 강퇴 이의 기능이 없기 때문이다. 나중에 분쟁이 생기면 운영자가
                    이 사유를 근거로 판단하므로, 비워두면 대응할 방법이 사라진다. 사유는 저장되고 강퇴 알림으로
                    당사자에게도 전달된다 — 운영자 열람 전용이 아니라는 뜻이니 그대로 보여줄 문구를 적어야 한다.

                    **재입장 대기는 강퇴가 반복될수록 두 배**가 된다(1주 → 2주 → 4주). 응답의
                    `rejoinAvailableAt` 이 그 방에 다시 들어올 수 있는 시각이며, 그 전에 가입을 시도하면
                    409 `JOIN_BLOCKED` + `reason=REJOIN_COOLDOWN` 이다. 사유가 무엇이든 대기 규칙은 같다.

                    방장은 자기 자신을 강퇴할 수 없다(400 `CANNOT_KICK_SELF`). 방을 떠나려면 탈퇴
                    (`DELETE /api/v1/challenges/{challengeId}/members/me`)를 쓴다.
                    이미 나간 사람을 대상으로 하면 404 `TARGET_NOT_MEMBER` 다.

                    신고 5명 누적에 의한 **자동 강퇴는 이 API 가 아니다** — 서버 배치 소관이며 여기는 방장의
                    수동 강퇴만 다룬다.
                    """
    )
    @ApiErrorCodes({ErrorCode.KICK_REASON_REQUIRED, ErrorCode.CANNOT_KICK_SELF,
            ErrorCode.NOT_CHALLENGE_OWNER, ErrorCode.TARGET_NOT_MEMBER,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/members/{targetUserId}")
    public ApiResponse<RoomAdminDtos.KickResponse> kick(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "방 id", required = true) @PathVariable UUID challengeId,
            @Parameter(description = "내보낼 멤버의 userId", required = true) @PathVariable UUID targetUserId,
            @RequestBody RoomAdminDtos.KickRequest request) {
        return ApiResponse.ok(service.kick(UUID.fromString(userId), challengeId, targetUserId, request.reason()));
    }

    @Tag(name = "Challenge Admin")
    @Operation(
            summary = "방장 권한 넘기기",
            description = """
                    방장 자리를 다른 ACTIVE 멤버에게 넘긴다. **수락 절차가 없다** — 호출 즉시 상대가 방장이 되고
                    나는 일반 멤버가 된다. 두 역할 변경은 한 트랜잭션이라 "방장이 둘"이거나 "없는" 순간은 없다.

                    상대에게는 방장이 됐다는 알림이 나가지만, 거절할 방법은 없으므로 넘기기 전에 확인을 받는 것은
                    클라이언트 몫이다.

                    **넘겨받은 방장은 3일 면책 대상이 아니다.** 면책(승계 3일)은 봇방장 방에서 스스로 손을 들어
                    방장이 된 경우(`owner/claim`)에만 붙는다 — 떠맡은 것과 자원한 것을 구분한다.

                    본인에게는 넘길 수 없고(400), 대상이 그 방의 ACTIVE 멤버가 아니면 404 다.
                    """
    )
    @ApiErrorCodes({ErrorCode.NOT_CHALLENGE_OWNER, ErrorCode.CANNOT_TRANSFER_TO_SELF,
            ErrorCode.TARGET_NOT_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PatchMapping("/owner")
    public ApiResponse<RoomAdminDtos.TransferResponse> transfer(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "방 id", required = true) @PathVariable UUID challengeId,
            @RequestBody RoomAdminDtos.TransferRequest request) {
        return ApiResponse.ok(service.transfer(UUID.fromString(userId), challengeId,
                UUID.fromString(request.targetUserId())));
    }

    @Tag(name = "Challenge Admin")
    @Operation(
            summary = "방장 되기 (봇방장 방 선착순 클레임)",
            description = """
                    방장이 권한을 넘기지 않고 나가면 방은 **봇방장 체제**가 된다(`ownerType=BOT`).
                    그 상태에서 멤버 누구나 이 API 로 방장이 될 수 있다 — **선착순 한 명**이다.

                    클라이언트는 방 홈 응답의 `ownerType=BOT` 을 보고 "방장 되기" 버튼을 노출한다.
                    경합에서 지면 409 `OWNER_ALREADY_EXISTS` 이므로, "이미 다른 분이 방장이 되었어요" 안내와
                    함께 방 화면을 갱신한다. 이미 사용자 방장이 있는 방에 호출해도 같은 코드다.

                    **클레임한 방장은 3일간 감점 면책**이다(`graceUntil`). 빈 자리를 대신 맡아준 사람이 곧바로
                    이탈 감점에 묶이면 아무도 손을 들지 않기 때문이다. 면책은 클레임한 본인뿐 아니라 승계 시점의
                    잔류 멤버 전원에게 적용된다.

                    그 방의 ACTIVE 멤버만 호출할 수 있다(403 `NOT_CHALLENGE_MEMBER`).
                    """
    )
    @ApiErrorCodes({ErrorCode.OWNER_ALREADY_EXISTS, ErrorCode.NOT_CHALLENGE_MEMBER,
            ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/owner/claim")
    public ApiResponse<RoomAdminDtos.ClaimResponse> claim(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "방 id", required = true) @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.claim(UUID.fromString(userId), challengeId));
    }
}
