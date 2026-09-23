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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 페이지2로 밀린 방장 운영 API — <b>기본값은 매핑되지 않는다.</b>
 *
 * <h4>왜 지우지 않고 남겨 두는가</h4>
 * 세 API 는 모두 2026-08-26 에 "페이지2 스펙아웃"으로 확정됐다. 다만 명세는 <b>재개 시 그대로
 * 쓰기 위해 보존</b>하기로 했으므로 코드도 같은 취급을 한다 — 지우면 재개할 때 동작·에러 코드·
 * 동시성 처리를 처음부터 다시 맞춰야 하고, 그 사이 서비스 계층은 계속 살아 있다
 * (자동 강퇴 3종이 {@code RoomAdminService} 를 쓴다).
 *
 * <h4>왜 플래그인가 — 클라이언트에서 감추는 것으로는 부족하다</h4>
 * 진입점을 숨겨도 매핑이 살아 있으면 호출된다. 방장 권한 축소는 "버튼을 안 보이게 하자"가 아니라
 * <b>방장이 사람에 대한 권한을 갖지 않는다</b>는 정책 결정이므로, 서버가 거절해야 성립한다.
 * 빈 자체를 만들지 않으므로 매핑이 등록되지 않고 Swagger 문서에도 나오지 않는다 — 404 다.
 *
 * <table>
 *   <tr><th>API</th><th>페이지1 대체 경로</th></tr>
 *   <tr><td>멤버 강퇴</td><td>자동 제재 3종(부정행위·연속 실패 3사이클·권한 미허용) — 전부 배치</td></tr>
 *   <tr><td>방장 위임</td><td>없음. 방장이 나가면 봇방장으로 자동 전환(정책 §11.2)</td></tr>
 *   <tr><td>방장 승계</td><td>없음. 봇방장 체제로 유지하며 승계 3일 면책도 함께 폐지</td></tr>
 * </table>
 */
@ConditionalOnProperty(name = "app.features.room-owner-admin.enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges/{challengeId}")
@RequiredArgsConstructor
public class LegacyRoomAdminController {

    private final RoomAdminService service;

    @Tag(name = "Challenge Admin")
    @Operation(
            summary = "[페이지2 스펙아웃] 멤버 강퇴",
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
            summary = "[페이지2 스펙아웃] 방장 권한 넘기기",
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
            summary = "[페이지2 스펙아웃] 방장 되기 (봇방장 방 선착순 클레임)",
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
