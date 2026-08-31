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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 신고 접수 · 개인 차단.
 *
 * <p>경로가 {@code /blacklist} 에서 {@code /blocks} 로 개명됐다(2026-08-26) — 용어를
 * <b>블랙리스트에서 차단으로</b> 바꾼 것이며 테이블명도 {@code user_blocks} 로 맞췄다.
 */
@Tag(name = "Report", description = "신고 접수 · 차단 — 차단 효과는 내 화면에만 적용된다")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final BlockService service;

    @Operation(
            summary = "신고 접수",
            description = """
                    **사유 선택만 받고 자유 텍스트는 받지 않는다.** 유저가 성실히 적어주지 않아 판단
                    재료로 신뢰할 수 없었고, 그 텍스트를 읽던 LLM 접수 필터도 함께 폐지됐다(2026-08-26).

                    동작은 **단일 동기 트랜잭션** 세 단계다. 비동기 단계도, 카운트 재계산도, 자동 강퇴 트리거도 없다.
                    1. **차단 등재** — 신고자 화면 효과를 즉시 적용하고 `hiddenEffect` 로 알려준다.
                    2. **컨텍스트 스냅샷** — 서버가 신고 시점의 대상 콘텐츠·소속 방·프로필·발생 화면을
                       모아 고정한다. 이후 원본이 수정·삭제돼도 **판단에는 스냅샷을 쓴다.**
                    3. **전건 적재** — 임계값 없이 쌓는다. **적재 자체는 어떤 제재도 발동시키지 않는다.**
                       제재는 운영자가 검토해 계정 단위로만 내린다.

                    **같은 대상 재신고는 구조적으로 불가능하다** — 접수 즉시 차단이 걸려 대상이 화면에서
                    사라지므로 신고 버튼이 노출되지 않는다. 클라이언트 우회로 들어온 요청은 차단을 재적용하고
                    건을 하나 더 적재한 뒤 **정상 201** 을 준다. 신고자에게는 정상 접수로 보여야 한다.

                    접수 결과는 **완료 안내만** 한다 — 처리 경과·결과는 알리지 않는다(익명성·보복 방지).

                    신고 총 횟수 제한도, 고정 임계값도 없다. 남용은 이상 패턴으로 탐지해 운영 검토로 보내고,
                    **운영자가 남용으로 확정하면** 신고 기능이 정지된다(403 `REPORT_SUSPENDED`).
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_REPORT_TARGET, ErrorCode.INVALID_REPORT_REASON,
            ErrorCode.CANNOT_REPORT_SELF, ErrorCode.REPORT_SUSPENDED, ErrorCode.ACCOUNT_LOCKED,
            ErrorCode.USER_NOT_FOUND, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportDtos.CreateResponse> report(@AuthenticationPrincipal String userId,
                                                         @RequestBody ReportDtos.CreateRequest request) {
        return ApiResponse.ok(service.report(UUID.fromString(userId), request));
    }

    @Operation(
            summary = "차단 목록 조회",
            description = """
                    내가 차단한 사용자와 챌린지다. 신고하면 자동으로 쌓이므로 별도의 "차단" API 는 없다.
                    차단은 내 화면에만 적용되는 개인 설정이라 이 목록도 나만 볼 수 있다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/api/v1/users/me/blocks")
    public ApiResponse<ReportDtos.BlockListResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.list(UUID.fromString(userId)));
    }

    @Operation(
            summary = "사용자 차단 해제",
            description = """
                    차단을 풀면 그 사람의 콘텐츠가 다시 정상적으로 보인다.

                    **신고 기록과 스냅샷은 지워지지 않는다.** 해제는 "이제 보여도 괜찮다"는 뜻이지
                    "신고를 취소한다"는 뜻이 아니다 — 해제를 취소로 처리하면 가해자가 피해자에게 해제를
                    종용해 기록을 지우는 경로가 생긴다. 운영자가 신고를 종결해도 차단은 그대로 유지된다.
                    """
    )
    @ApiErrorCodes({ErrorCode.BLOCK_ENTRY_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/api/v1/users/me/blocks/users/{blockedUserId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockUser(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "차단을 풀 사용자 id", required = true) @PathVariable UUID blockedUserId) {
        return ApiResponse.ok(service.unblockUser(UUID.fromString(userId), blockedUserId));
    }

    @Operation(
            summary = "챌린지 차단 해제",
            description = "차단을 풀면 그 챌린지가 다시 탐색 목록에 나타난다. 사용자 차단과 마찬가지로 **신고 기록은 남는다.**"
    )
    @ApiErrorCodes({ErrorCode.BLOCK_ENTRY_NOT_FOUND, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/api/v1/users/me/blocks/challenges/{challengeId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockChallenge(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "차단을 풀 챌린지 id", required = true) @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.unblockChallenge(UUID.fromString(userId), challengeId));
    }
}
