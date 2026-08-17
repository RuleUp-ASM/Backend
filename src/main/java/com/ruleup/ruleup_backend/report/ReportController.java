package com.ruleup.ruleup_backend.report;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 신고 접수 · 블랙리스트(개인 차단) 관리. */
@Tag(name = "Report", description = "신고 접수 · 블랙리스트 — 차단 효과는 내 화면에만 적용된다")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final BlacklistService service;

    @Operation(
            summary = "신고 접수",
            description = """
                    사용자 또는 챌린지를 신고한다. `targetType` 에 따라 `targetUserId` · `targetChallengeId`
                    중 필요한 쪽을 채운다. 방 안에서 발생한 사용자 신고는 `targetChallengeId` 가 필수이며,
                    공지·댓글·인증 이벤트 신고는 해당 `contextId` 도 함께 보낸다.

                    **접수 즉시 개인 차단이 걸린다**(`blacklisted:true`). 심사 결과를 기다리지 않는 이유는,
                    신고할 만큼 불쾌한 상대를 심사가 끝날 때까지 계속 봐야 한다면 신고가 아무 의미가 없기 때문이다.
                    차단 효과는 **내 화면에만** 적용된다 — 상대는 아무것도 알지 못한다.
                    스레드에서는 상대의 인증 이벤트가 임시 닉네임·기본 이미지로 가려진 채 남고,
                    랭킹에서도 같은 방식으로 가려진다.

                    **같은 대상을 1주 안에 다시 신고하면 `duplicate:true`** 로 201 이 내려간다. 에러가 아니며
                    차단도 그대로 유지되지만, 제재 카운트에는 반영되지 않는다(같은 사람이 반복 신고해 남을
                    몰아내는 것을 막는다).

                    접수된 신고는 백그라운드 필터를 거치고 **통과한 건만** 제재 카운트에 들어간다. 필터에서 걸러져도
                    신고자에게는 아무 표시가 나지 않는다 — 어떤 신고가 유효한지 알려주면 우회 방법을 알려주는 셈이다.

                    `detail` 은 모든 신고에서 필수다(최대 1000자). 본인은 신고할 수 없다.

                    신고를 남용해 제재를 받은 상태면 403 `REPORT_SUSPENDED` 이며, `error.reason` 에 정지 해제
                    시각이 실린다. 정지 기간은 반복될수록 두 배가 된다(1주 → 2주 → 4주).
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_REPORT_TARGET, ErrorCode.INVALID_REPORT_REASON,
            ErrorCode.DETAIL_REQUIRED, ErrorCode.CANNOT_REPORT_SELF, ErrorCode.REPORT_SUSPENDED,
            ErrorCode.USER_NOT_FOUND, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportDtos.CreateResponse> report(@AuthenticationPrincipal String userId,
                                                         @RequestBody ReportDtos.CreateRequest request) {
        return ApiResponse.ok(service.report(UUID.fromString(userId), request));
    }

    @Operation(
            summary = "블랙리스트 조회",
            description = """
                    내가 차단한 사용자와 챌린지 목록이다. 신고하면 자동으로 여기에 쌓이므로 별도의 "차단" API 는 없다.

                    차단은 내 화면에만 적용되는 개인 설정이라 이 목록도 나만 볼 수 있다.
                    설정 화면에서 해제하려면 아래 두 API 를 쓴다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/api/v1/users/me/blacklist")
    public ApiResponse<ReportDtos.BlacklistResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.list(UUID.fromString(userId)));
    }

    @Operation(
            summary = "사용자 차단 해제",
            description = """
                    차단을 풀면 그 사람의 콘텐츠가 다시 정상적으로 보인다.

                    **신고 기록과 제재 카운트는 지워지지 않는다.** 차단 해제는 "이제 보여도 괜찮다"는 뜻이지
                    "신고를 취소한다"는 뜻이 아니며, 해제를 취소로 처리하면 가해자가 피해자에게 해제를 종용해
                    카운트를 지우는 경로가 생긴다.

                    차단한 적이 없는 사용자면 404 `BLACKLIST_ENTRY_NOT_FOUND` 다.
                    """
    )
    @ApiErrorCodes({ErrorCode.BLACKLIST_ENTRY_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/api/v1/users/me/blacklist/users/{blockedUserId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockUser(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "차단을 풀 사용자 id", required = true) @PathVariable UUID blockedUserId) {
        return ApiResponse.ok(service.unblockUser(UUID.fromString(userId), blockedUserId));
    }

    @Operation(
            summary = "챌린지 차단 해제",
            description = """
                    차단을 풀면 그 챌린지가 다시 탐색 목록에 나타난다.
                    사용자 차단 해제와 마찬가지로 **신고 기록은 남는다.**

                    차단한 적이 없는 챌린지면 404 `BLACKLIST_ENTRY_NOT_FOUND` 다.
                    """
    )
    @ApiErrorCodes({ErrorCode.BLACKLIST_ENTRY_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/api/v1/users/me/blacklist/challenges/{challengeId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockChallenge(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "차단을 풀 챌린지 id", required = true) @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.unblockChallenge(UUID.fromString(userId), challengeId));
    }
}
