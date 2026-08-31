package com.ruleup.ruleup_backend.admin.controller;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.service.AdminOpsService;
import com.ruleup.ruleup_backend.admin.service.AdminReviewService;
import com.ruleup.ruleup_backend.admin.service.AdminSanctionService;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 운영자 백오피스 — 백오피스 공통 5-2.
 *
 * <p>전용 클라이언트 형태는 미정이지만 <b>접근 통제·감사 로그·2단계 확인은 형태와 무관하게
 * 필수</b>라 서버에 먼저 세운다. 경로 prefix 전체에 인터셉터가 걸려 있어 엔드포인트를 추가해도
 * 권한 검사를 빠뜨릴 자리가 없다.
 */
@Tag(name = "Admin", description = "운영자 백오피스 — 신고 검토 · 제재 · 직권 폐쇄 · 이상탐지 · 장애 구제 · 공지")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminReviewService reviewService;
    private final AdminSanctionService sanctionService;
    private final AdminOpsService opsService;

    // ===== 신고 검토 =====

    @Operation(summary = "신고 검토 큐",
            description = "**피신고자 또는 챌린지 단위로 묶어** 내린다. 정렬은 접수 순이며 **처리 기한은 없다**.")
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/reports")
    public ApiResponse<AdminDtos.ReportQueueResponse> reports(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(reviewService.queue(UUID.fromString(userId)));
    }

    @Operation(summary = "신고 상세",
            description = """
                    **신고 시점 스냅샷**이다 — 원본이 수정·삭제돼도 이 값으로 검토한다.
                    **신고자 신원은 응답에 없다.**

                    스냅샷 열람은 개인정보 열람이라 감사 로그에 `SNAPSHOT_VIEW` 로 따로 남는다.
                    """)
    @ApiErrorCodes({ErrorCode.REPORT_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/reports/{reportId}")
    public ApiResponse<AdminDtos.ReportDetail> reportDetail(@AuthenticationPrincipal String userId,
                                                             @PathVariable String reportId) {
        return ApiResponse.ok(reviewService.detail(
                UUID.fromString(userId), UUID.fromString(reportId)));
    }

    @Operation(summary = "검토 결과 확정",
            description = """
                    문제없음 종결(`NO_ACTION`) 또는 제재로 진행(`SANCTIONED`).

                    **종결해도 각 신고자의 개인 차단은 유지**된다 — 차단은 제재가 아니라 개인 선택이다.
                    """)
    @ApiErrorCodes({ErrorCode.REVIEW_ALREADY_RESOLVED, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/reports/{reportId}/resolve")
    public ApiResponse<AdminDtos.ResolveResponse> resolve(@AuthenticationPrincipal String userId,
                                                          @PathVariable String reportId,
                                                          @RequestBody AdminDtos.ResolveRequest request) {
        return ApiResponse.ok(reviewService.resolve(
                UUID.fromString(userId), UUID.fromString(reportId), request));
    }

    // ===== 제재 =====

    @Operation(summary = "계정 제재 집행",
            description = """
                    `FEATURE_SUSPENSION` / `LOCK` / `BAN`. **사유 입력이 필수**다.

                    `confirmationToken` 없이 보내면 **428** 과 함께 재확인 요약(`preview`)과 토큰이 내려온다.
                    토큰은 **대상·내용에 묶여** 있어 다른 요청에는 통하지 않는다 —
                    서버가 요구하지 않으면 클라이언트 모달만으로는 오조작을 막지 못한다.

                    집행 순서는 감사 로그 → 제재 → 상태 전이 → 밴리스트 → **커밋** → 고지·자동 탈퇴다.
                    알림 실패가 제재를 롤백시키면 안 되고, 제재가 롤백됐는데 고지만 나가면 더 안 된다.
                    """)
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.SANCTION_ALREADY_ACTIVE,
            ErrorCode.INVALID_REQUEST, ErrorCode.USER_NOT_FOUND,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/users/{targetUserId}/sanctions")
    public ApiResponse<AdminDtos.SanctionResponse> sanction(@AuthenticationPrincipal String userId,
                                                            @PathVariable String targetUserId,
                                                            @RequestBody AdminDtos.SanctionRequest request) {
        return ApiResponse.ok(sanctionService.apply(
                UUID.fromString(userId), UUID.fromString(targetUserId), request));
    }

    @Operation(summary = "제재 해제",
            description = "재검토 인용 시. 원본을 지우지 않고 해제 시각만 남기며, 다른 활성 제재가 없으면 계정이 복귀한다.")
    @ApiErrorCodes({ErrorCode.USER_NOT_FOUND, ErrorCode.REVIEW_ALREADY_RESOLVED,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/users/{targetUserId}/sanctions/{sanctionId}")
    public ApiResponse<Void> revokeSanction(@AuthenticationPrincipal String userId,
                                            @PathVariable String targetUserId,
                                            @PathVariable String sanctionId) {
        sanctionService.revoke(UUID.fromString(userId), UUID.fromString(targetUserId),
                UUID.fromString(sanctionId));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "유저 판단 근거 통합 뷰",
            description = "계정 상태 · **자동/직권 제재를 별개 배열로** · 이상탐지 이력 · 신고 건수. 판단에 불필요한 항목은 없다.")
    @ApiErrorCodes({ErrorCode.USER_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/users/{targetUserId}")
    public ApiResponse<AdminDtos.UserView> userView(@AuthenticationPrincipal String userId,
                                                     @PathVariable String targetUserId) {
        return ApiResponse.ok(opsService.userView(
                UUID.fromString(userId), UUID.fromString(targetUserId)));
    }

    // ===== 이상탐지 · 폐쇄 · 구제 · 공지 =====

    @Operation(summary = "이상탐지 신호",
            description = "신고 남용 · 이의 남용 · 모더레이션 회피. **탐지만으로는 제재하지 않는다** — 검토 대상 목록이다.")
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/anomalies")
    public ApiResponse<AdminDtos.AnomalyResponse> anomalies(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(opsService.anomalies(UUID.fromString(userId)));
    }

    @Operation(summary = "챌린지 직권 폐쇄",
            description = """
                    **영향 인원 수를 먼저 응답**해 오조작을 막는다(428 + `preview.affectedMemberCount`).

                    집행하면 일반 참여자는 **감점 없이** 자동 탈퇴하고 랭킹에서만 빠진다. 방장은 별도 제재 대상이다.
                    """)
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.CHALLENGE_NOT_FOUND,
            ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/challenges/{challengeId}/close")
    public ApiResponse<AdminDtos.CloseResponse> close(@AuthenticationPrincipal String userId,
                                                      @PathVariable String challengeId,
                                                      @RequestBody AdminDtos.CloseRequest request) {
        return ApiResponse.ok(opsService.closeChallenge(
                UUID.fromString(userId), UUID.fromString(challengeId), request));
    }

    @Operation(summary = "장애 구제",
            description = "기간과 범위를 지정해 판정을 일괄 제외한다. **성공 처리가 아니라 분모에서 제외**하는 중립 처리다.")
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/outage-relief")
    public ApiResponse<AdminDtos.ReliefResponse> outageRelief(@AuthenticationPrincipal String userId,
                                                              @RequestBody AdminDtos.ReliefRequest request) {
        return ApiResponse.ok(opsService.applyRelief(UUID.fromString(userId), request));
    }

    @Operation(summary = "운영 공지 발행",
            description = "점검·장애·약관·종료 공지. **필수(A) 알림으로 나간다** — 끌 수 없고 야간에도 즉시 발송된다.")
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/notices")
    public ApiResponse<AdminDtos.NoticeResponse> notice(@AuthenticationPrincipal String userId,
                                                        @RequestBody AdminDtos.NoticeRequest request) {
        return ApiResponse.ok(opsService.publishNotice(UUID.fromString(userId), request));
    }
}
