package com.ruleup.ruleup_backend.admin.controller;

import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.dto.AdminInquiryDtos;
import com.ruleup.ruleup_backend.admin.service.AdminDashboardService;
import com.ruleup.ruleup_backend.admin.service.AdminInquiryService;
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
 * 운영자 백오피스 — 공통 5-2 · <b>5-2-1(2026-09-09 확정)</b>.
 *
 * <p>구현 형태는 전용 웹 콘솔로 확정됐다(`RuleUp-ASM/AdminPage`). 다만 <b>접근 통제·감사 로그·
 * 2단계 확인은 형태와 무관하게 필수</b>라 서버가 소유한다. 경로 prefix 전체에 인터셉터가 걸려
 * 있어 엔드포인트를 추가해도 권한 검사를 빠뜨릴 자리가 없다.
 */
@Tag(name = "Admin", description = "운영자 백오피스 — 대시보드 · 신고 · 제재 · CS · 직권 폐쇄 · 이상탐지 · 공지")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminReviewService reviewService;
    private final AdminSanctionService sanctionService;
    private final AdminOpsService opsService;
    private final AdminInquiryService inquiryService;
    private final AdminDashboardService dashboardService;

    // ===== 대시보드 =====

    @Operation(summary = "모니터링 요약", description = """
            운영 지표와 **가드레일**을 한 응답에 담는다.

            둘은 층이 다르다 — 지표(적체·SLA·접수 비중)는 추세를 보고 운영을 조정하는 값이지만,
            `guardrails` 는 **0이어야 하는 값**이다. 백오피스 § 3 이 「고지 없이 집행된 직권 제재
            0건」처럼 못박아 둔 것들이며 **0이 아니면 지표가 아니라 경고로 띄운다.**
            """)
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/dashboard/summary")
    public ApiResponse<AdminDtos.DashboardSummary> dashboard(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false, defaultValue = "7d") String range) {
        return ApiResponse.ok(dashboardService.summary(UUID.fromString(userId), range));
    }

    // ===== 신고 검토 =====

    @Operation(summary = "신고 검토 큐(건별)", description = """
            정렬은 접수 순이며 **처리 기한 필드를 두지 않는다.** 커서는 **불투명 문자열**이라
            서버가 준 값을 그대로 되돌려 보낸다.

            `targetReportCount` 는 **참고 지표로만** 쓴다 — 임계값이 아니고, 신고 수가 제재를
            발동시키는 경로는 존재하지 않는다.
            """)
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/reports")
    public ApiResponse<AdminDtos.ReportQueueResponse> reports(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) Boolean flagged,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reviewService.queue(UUID.fromString(userId), status, targetType,
                reason, flagged, q, cursor, size));
    }

    @Operation(summary = "신고 검토 큐(대상 단위)", description = """
            **피신고자 또는 챌린지 단위로 묶어** 내린다. 같은 대상의 신고를 하나씩 보면 판단이
            느려지고 같은 사안을 여러 번 판단하게 된다. 건별 목록과 페이징 단위가 달라 경로를 나눴다.
            """)
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/reports/targets")
    public ApiResponse<AdminDtos.ReportTargetsResponse> reportTargets(
            @AuthenticationPrincipal String userId) {
        return ApiResponse.ok(reviewService.targets(UUID.fromString(userId)));
    }

    @Operation(summary = "신고 상세", description = """
            **신고 시점 스냅샷**이다 — 원본이 수정·삭제돼도 이 값으로 검토한다.
            **신고자 신원은 응답에 없다.** 접수자 남용은 `reporterFlagged` 불리언 하나로만 나가고
            상세는 이상탐지가 담당한다.

            스냅샷 이미지는 **단기 접근 주소**로만 나가며 `snapshot.imageUrlsExpireAt` 에 만료가 실린다.
            열람은 개인정보 열람이라 감사 로그에 `SNAPSHOT_VIEW` 로 따로 남는다.
            """)
    @ApiErrorCodes({ErrorCode.REPORT_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/reports/{reportId}")
    public ApiResponse<AdminDtos.ReportDetail> reportDetail(@AuthenticationPrincipal String userId,
                                                             @PathVariable UUID reportId) {
        return ApiResponse.ok(reviewService.detail(UUID.fromString(userId), reportId));
    }

    @Operation(summary = "검토 결과 확정", description = """
            `decision` 은 **둘뿐이다** — `NO_ACTION`(문제없음 종결) · `MODERATION_REJECT`(콘텐츠만
            문제 → 거부 처리로 전환). 제재와 직권 폐쇄는 각자의 엔드포인트가 근거 신고를 함께
            종결시키므로 여기서 낼 결정이 아니다.

            `cascadeToTarget` 이 true 여도 **함께 종결할 대상은 서버가 다시 계산한다.**

            **종결해도 각 신고자의 개인 차단은 유지**된다 — 차단은 제재가 아니라 개인 선택이다.
            """)
    @ApiErrorCodes({ErrorCode.REVIEW_ALREADY_RESOLVED, ErrorCode.REPORT_NOT_FOUND,
            ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/reports/{reportId}/resolve")
    public ApiResponse<AdminDtos.ResolveResponse> resolve(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID reportId,
                                                          @RequestBody AdminDtos.ResolveRequest request) {
        return ApiResponse.ok(reviewService.resolve(UUID.fromString(userId), reportId, request));
    }

    // ===== 제재 =====

    @Operation(summary = "계정 제재 집행", description = """
            수단은 **3종 + `permanent` 플래그**다 — 운영자 제재 정책 § 5.4 가 영구 정지를 별도
            제재 종류로 두지 않는다. **사유 입력이 필수**이며 고지 알림에 그대로 실린다.

            `confirmationToken` 없이 보내면 **428** 과 함께 서버가 계산한 재확인 봉투
            (`error.confirmation`)가 내려온다. 토큰은 **대상·내용에 묶여** 있어 다른 요청에는 통하지
            않는다 — 서버가 요구하지 않으면 클라이언트 모달만으로는 오조작을 막지 못한다.

            집행 순서는 감사 로그 → 제재 → 상태 전이 → 밴리스트 → **커밋** → 고지·자동 탈퇴다.
            알림 실패가 제재를 롤백시키면 안 되고, 제재가 롤백됐는데 고지만 나가면 더 안 된다.
            """)
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.SANCTION_ALREADY_ACTIVE,
            ErrorCode.INVALID_REQUEST, ErrorCode.USER_NOT_FOUND,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/users/{targetUserId}/sanctions")
    public ApiResponse<AdminDtos.SanctionResponse> sanction(@AuthenticationPrincipal String userId,
                                                            @PathVariable UUID targetUserId,
                                                            @RequestBody AdminDtos.SanctionRequest request) {
        return ApiResponse.ok(sanctionService.apply(UUID.fromString(userId), targetUserId, request));
    }

    @Operation(summary = "제재 해제", description = """
            재검토 인용 시. 원본을 지우지 않고 해제 시각만 남기며, 다른 활성 제재가 없으면 계정이
            복귀한다. **해제 사유도 감사 로그에 남는다** — 건 근거만 있고 푼 근거가 없으면 재검토
            대응에서 절반의 이야기밖에 못 한다.
            """)
    @ApiErrorCodes({ErrorCode.USER_NOT_FOUND, ErrorCode.APPEAL_ALREADY_USED,
            ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @DeleteMapping("/users/{targetUserId}/sanctions/{sanctionId}")
    public ApiResponse<AdminDtos.SanctionItem> revokeSanction(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID targetUserId,
            @PathVariable UUID sanctionId,
            @RequestBody(required = false) AdminDtos.RevokeRequest request) {
        return ApiResponse.ok(sanctionService.revoke(
                UUID.fromString(userId), targetUserId, sanctionId, request));
    }

    @Operation(summary = "제재 이력 전체", description = """
            유저 하위 경로만으로는 **최근 무엇이 집행됐는지**를 볼 수 없어 따로 둔다.
            `track` · `type` · `onlyActive` 로 거른다. `active` 는 **서버가 계산한다** —
            진행 중·동결·영구 세 경우를 클라이언트가 판단하면 동결된 계정이 「제재 없음」으로 보인다.
            """)
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/sanctions")
    public ApiResponse<AdminDtos.SanctionListResponse> sanctions(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String track,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean onlyActive,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(opsService.sanctions(UUID.fromString(userId), track, type,
                onlyActive, cursor, size));
    }

    @Operation(summary = "유저 판단 근거 통합 뷰",
            description = "계정 상태 · **자동/직권 제재를 별개 배열로** · 이상탐지 이력 · 신고/참여 집계. 판단에 불필요한 항목은 없다.")
    @ApiErrorCodes({ErrorCode.USER_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/users/{targetUserId}")
    public ApiResponse<AdminDtos.UserView> userView(@AuthenticationPrincipal String userId,
                                                     @PathVariable UUID targetUserId) {
        return ApiResponse.ok(opsService.userView(UUID.fromString(userId), targetUserId));
    }

    // ===== CS 문의 =====

    @Operation(summary = "문의 큐", description = """
            **접수 순(오래된 것 먼저)** 이다. 최신순이면 가장 오래 기다린 문의가 목록 맨 아래로
            밀려 SLA 를 어기는 건부터 눈에서 사라진다.
            """)
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/inquiries")
    public ApiResponse<AdminInquiryDtos.QueueResponse> inquiries(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(inquiryService.queue(UUID.fromString(userId), status, category,
                q, cursor, size));
    }

    @Operation(summary = "문의 상세", description = """
            분류별 **1차 확인 항목**(앱 운영 정책 § 5.1)을 바로 볼 수 있게 계정 상태·활성 제재와
            자동 첨부 값을 함께 내린다.

            상세 열람은 **개인정보 열람**이라 감사 로그에 `INQUIRY_VIEW` 로 따로 남는다.
            """)
    @ApiErrorCodes({ErrorCode.INQUIRY_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/inquiries/{inquiryId}")
    public ApiResponse<AdminInquiryDtos.Detail> inquiry(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID inquiryId) {
        return ApiResponse.ok(inquiryService.detail(UUID.fromString(userId), inquiryId));
    }

    @Operation(summary = "문의 답변", description = """
            **답변 등록이 곧 종결**이다. 재등록은 409 `ALREADY_ANSWERED` — 유저 화면이 열람
            전용이라 정정 답변을 보낼 자리가 애초에 없다.

            등록하면 알림으로 통지한다(분류 일반 · 토글 계정 · 야간에는 다음 날 08:00 발송).
            """)
    @ApiErrorCodes({ErrorCode.ALREADY_ANSWERED, ErrorCode.INQUIRY_NOT_FOUND,
            ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/inquiries/{inquiryId}/answer")
    public ApiResponse<AdminInquiryDtos.Detail> answerInquiry(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID inquiryId,
            @RequestBody AdminInquiryDtos.AnswerRequest request) {
        return ApiResponse.ok(inquiryService.answer(UUID.fromString(userId), inquiryId, request));
    }

    @Operation(summary = "문의 분류 변경", description = """
            유저가 잘못 고른 분류를 운영자가 바꾼다. **변경 사실은 유저에게 노출하지 않으며**,
            원본 분류는 그대로 남아 「분류별 접수 비중」 지표를 오염시키지 않는다.
            """)
    @ApiErrorCodes({ErrorCode.INQUIRY_NOT_FOUND, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PatchMapping("/inquiries/{inquiryId}/category")
    public ApiResponse<AdminInquiryDtos.Detail> reclassifyInquiry(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID inquiryId,
            @RequestBody AdminInquiryDtos.CategoryRequest request) {
        return ApiResponse.ok(inquiryService.reclassify(UUID.fromString(userId), inquiryId, request));
    }

    // ===== 이상탐지 =====

    @Operation(summary = "이상탐지 신호", description = """
            신고 남용 · 이의 남용 · 모더레이션 회피. **탐지만으로는 제재하지 않는다** —
            일괄 처리·자동 제재 경로를 두지 않는 것이 「검토 없이 발동된 계정 제재 0건」의 실체다.

            `summary` 는 **서버가 만든 판단 근거 문장**이다. 점수만으로는 사람이 판단할 수 없다.
            """)
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/anomalies")
    public ApiResponse<AdminDtos.AnomalyResponse> anomalies(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String signalType,
            @RequestParam(required = false, defaultValue = "true") Boolean onlyUnreviewed,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(opsService.anomalies(UUID.fromString(userId), signalType,
                onlyUnreviewed, minScore, cursor, size));
    }

    @Operation(summary = "이상탐지 검토 종료", description = """
            **제재로 승격하는 경로가 아니다.** "봤고 이러이러해서 넘겼다"는 기록이며, 제재가
            필요하면 제재 엔드포인트를 따로 호출한다. 검토 메모를 함께 받는다 — 왜 넘겼는지가
            남아야 같은 신호가 다시 올라왔을 때 판단을 반복하지 않는다.
            """)
    @ApiErrorCodes({ErrorCode.ANOMALY_NOT_FOUND, ErrorCode.REVIEW_ALREADY_RESOLVED,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/anomalies/{signalId}/review")
    public ApiResponse<AdminDtos.AnomalyItem> reviewAnomaly(
            @AuthenticationPrincipal String userId,
            @PathVariable UUID signalId,
            @RequestBody(required = false) AdminDtos.AnomalyReviewRequest request) {
        return ApiResponse.ok(opsService.reviewAnomaly(UUID.fromString(userId), signalId, request));
    }

    // ===== 챌린지 =====

    @Operation(summary = "챌린지 상세", description = "폐쇄 판단 전 방 상태·인원·신고 수를 확인한다.")
    @ApiErrorCodes({ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/challenges/{challengeId}")
    public ApiResponse<AdminDtos.ChallengeDetail> challenge(@AuthenticationPrincipal String userId,
                                                             @PathVariable UUID challengeId) {
        return ApiResponse.ok(opsService.challenge(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "챌린지 직권 폐쇄", description = """
            **영향 인원 수를 먼저 응답**해 오조작을 막는다 — 428 의 `confirmation.sideEffects` 가
            그 역할을 겸하므로 별도 미리보기 엔드포인트를 두지 않는다. 확인과 집행이 같은 토큰으로
            묶이는 편이 안전하다.

            집행하면 일반 참여자는 **감점 없이** 자동 탈퇴하고 랭킹에서만 빠진다. 방장은 사안에
            따라 별도 제재 대상이며 **폐쇄가 자동으로 제재를 걸지는 않는다.**
            """)
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.CHALLENGE_NOT_FOUND,
            ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/challenges/{challengeId}/close")
    public ApiResponse<AdminDtos.ChallengeDetail> close(@AuthenticationPrincipal String userId,
                                                        @PathVariable UUID challengeId,
                                                        @RequestBody AdminDtos.CloseRequest request) {
        return ApiResponse.ok(opsService.closeChallenge(UUID.fromString(userId), challengeId, request));
    }

    // ===== 장애 구제 · 운영 공지 =====

    @Operation(summary = "장애 구제",
            description = "기간과 범위를 지정해 판정을 일괄 제외한다. **성공 처리가 아니라 분모에서 제외**하는 중립 처리다.")
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/outage-relief")
    public ApiResponse<AdminDtos.ReliefResponse> outageRelief(@AuthenticationPrincipal String userId,
                                                              @RequestBody AdminDtos.ReliefRequest request) {
        return ApiResponse.ok(opsService.applyRelief(UUID.fromString(userId), request));
    }

    @Operation(summary = "운영 공지 발행", description = """
            점검·장애·약관·종료 공지. **푸시가 나가지 않는다** — 공지는 알림함에만 적재된다
            (알림 테크 스펙 오픈 이슈 #8, 2026-09-07 확정). 그래서 응답에 푸시 통계 필드가 없다.

            **`kind=MARKETING` 만 예외다.** 광고성 정보는 수신 **동의자에게만** 가고 푸시도 나간다
            (정보통신망법 — 발송 창 08~21시). 미동의자는 알림함에도 적재되지 않는다.

            전체 팬아웃이라 되돌릴 수 없어 **428 을 탄다.** 적재는 팬아웃 잡이 청크 단위로 한다.
            """)
    @ApiErrorCodes({ErrorCode.CONFIRMATION_REQUIRED, ErrorCode.INVALID_REQUEST,
            ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/notices")
    public ApiResponse<AdminDtos.NoticeResponse> notice(@AuthenticationPrincipal String userId,
                                                        @RequestBody AdminDtos.NoticeRequest request) {
        return ApiResponse.ok(opsService.publishNotice(UUID.fromString(userId), request));
    }

    @Operation(summary = "공지 발행 이력", description = "발행·예약·취소 상태를 함께 본다.")
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/notices")
    public ApiResponse<AdminDtos.NoticeListResponse> notices(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(opsService.notices(UUID.fromString(userId)));
    }

    @Operation(summary = "공지 예약 취소", description = """
            **이미 적재된 공지는 회수되지 않는다.** 취소는 팬아웃 전에만 의미가 있으므로 발송이
            끝난 공지는 409 `ANNOUNCEMENT_ALREADY_SENT` 다.
            """)
    @ApiErrorCodes({ErrorCode.ANNOUNCEMENT_NOT_FOUND, ErrorCode.ANNOUNCEMENT_ALREADY_SENT,
            ErrorCode.REVIEW_ALREADY_RESOLVED, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/notices/{announcementId}/cancel")
    public ApiResponse<AdminDtos.NoticeResponse> cancelNotice(@AuthenticationPrincipal String userId,
                                                              @PathVariable UUID announcementId) {
        return ApiResponse.ok(opsService.cancelNotice(UUID.fromString(userId), announcementId));
    }
}
