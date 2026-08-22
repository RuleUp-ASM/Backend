package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationRequest;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationResponse;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationUpdateResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionDecisionRequest;
import com.ruleup.ruleup_backend.verification.dto.ObjectionDecisionResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionResponse;
import com.ruleup.ruleup_backend.verification.dto.ObjectionSubmitRequest;
import com.ruleup.ruleup_backend.verification.dto.PendingReviewsResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsResponse;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateRequest;
import com.ruleup.ruleup_backend.verification.dto.ScreenAppsUpdateResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupRequest;
import com.ruleup.ruleup_backend.verification.dto.SetupRequirementResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupResponse;
import com.ruleup.ruleup_backend.verification.dto.TodayVerificationResponse;
import com.ruleup.ruleup_backend.verification.service.ObjectionService;
import com.ruleup.ruleup_backend.verification.service.PendingReviewsService;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
import com.ruleup.ruleup_backend.verification.service.VerificationReadService;
import com.ruleup.ruleup_backend.verification.service.VerificationSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 챌린지별 인증 API. base = /api/v1/challenges/{challengeId}.
 * 셋업(앵커·대상 앱 바인딩) → 오늘 인증 결과 조회 → 수동 체크 제출 순으로 쓰인다.
 */
@Tag(name = "인증 구현 - 챌린지", description = "셋업 · 인증 장소/앱 · 오늘 인증 결과 · 수동 인증 제출")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class VerificationChallengeController {

    private final VerificationReadService readService;
    private final VerificationManualService manualService;
    private final VerificationSetupService setupService;
    private final ObjectionService objectionService;
    private final PendingReviewsService pendingReviewsService;

    // ===== 최초 진입 셋업 =====

    @Operation(summary = "최초 진입 시 설정 필요한 정보 조회",
            description = """
                    챌린지 첫 진입 때 "인증을 시작하려면 뭘 묶어야 하는지"(장소 앵커·대상 앱)를 알려주는 읽기 전용 조회.

                    OS 권한은 생성/가입 단계에서 클라가 이미 받았으므로 여기서 받지 않는다 —
                    `requiredPermissions`는 클라가 스스로 재확인하는 참고 목록이고 서버는 보유 여부를 저장하지 않는다.

                    참여(ACTIVE) 멤버만.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.SESSION_EXPIRED,
            ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND})
    @GetMapping("/{challengeId}/setup")
    public ApiResponse<SetupRequirementResponse> setupRequirements(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId) {
        return ApiResponse.ok(setupService.getRequirements(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "인증 장소/인증 앱 제출 - 최초 진입 시",
            description = """
                    앵커·대상 앱 **바인딩 제출**. 다 채우면 `READY`(평가 대상 진입), 모자라면 `missing[]`과 함께 `PENDING_SETUP`.

                    **첫 설정은 변경 횟수를 소진하지 않는다** — 월 1회 제한은 이후 *변경*(PUT)부터.
                    - 앵커: 최대 3개. **반경은 클라가 정하지 않는다**(서버 설정 단일값이라 요청에 `radiusM`이 없다).
                    - 대상 앱: 1~10개, `packageName` 중복 불가.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_ANCHOR, ErrorCode.ANCHOR_LIMIT_EXCEEDED, ErrorCode.INVALID_APP,
            ErrorCode.LOGIN_REQUIRED, ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND})
    @PostMapping("/{challengeId}/setup")
    public ApiResponse<SetupResponse> setup(@AuthenticationPrincipal String userId,
                                            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId,
                                            @RequestBody SetupRequest request) {
        return ApiResponse.ok(setupService.setup(UUID.fromString(userId), challengeId, request));
    }

    // ===== 내 인증 장소(앵커) =====

    @Operation(summary = "내 인증장소 조회",
            description = """
                    위치 셋업/수정 화면에 다시 들어왔을 때 지도에 이전 핀을 복원하기 위한 조회. 참여(ACTIVE) 멤버만.

                    반경은 유저 값이 아니라 **서버 설정 단일값**이라 앵커에 들어있지 않고 `serverRadiusM`으로 따로 내려간다.
                    이번 달 변경 가능 여부(`changeAvailable`)도 함께 준다 — 수정 버튼 활성/비활성용.

                    바인딩된 앵커가 하나도 없으면 `GEOFENCE_NOT_CONFIGURED` 로 실패한다 — 첫 설정은 setup API로.
                    """)
    @ApiErrorCodes({ErrorCode.GEOFENCE_NOT_CONFIGURED, ErrorCode.LOGIN_REQUIRED,
            ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND})
    @GetMapping("/{challengeId}/my-location")
    public ApiResponse<MemberLocationResponse> getLocation(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId) {
        return ApiResponse.ok(setupService.getMyLocation(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "내 인증장소 수정",
            description = """
                    보낸 목록으로 앵커 세트 **전체를 갈아끼운다**(부분 수정 아님). 최대 3개.

                    - **변경은 월 1회.** "저장 1회"가 단위라 앵커 하나만 고쳐도 그 달 횟수를 소진한다(매월 1일 00:00 KST 리셋).
                    - **인증 윈도우가 진행 중이면 거부**한다(그날 판정을 흔들 수 없게) — 익일 재시도. 평상시엔 즉시 적용.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_ANCHOR, ErrorCode.ANCHOR_LIMIT_EXCEEDED, ErrorCode.GEOFENCE_NOT_CONFIGURED,
            ErrorCode.LOGIN_REQUIRED, ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND,
            ErrorCode.LOCATION_LOCKED_IN_WINDOW, ErrorCode.SETTING_CHANGE_LIMIT})
    @PutMapping("/{challengeId}/my-location")
    public ApiResponse<MemberLocationUpdateResponse> updateLocation(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId,
            @RequestBody MemberLocationRequest request) {
        return ApiResponse.ok(setupService.updateLocation(UUID.fromString(userId), challengeId, request));
    }

    // ===== 내 스크린타임 대상 앱 =====

    @Operation(summary = "내 스크린타임 앱 조회",
            description = """
                    앱 셋업/수정 화면 재진입 시 목록 복원용. 참여(ACTIVE) 멤버만.

                    앱 교체는 항상 익일 00:00부터 적용되는 구조라, 현재 적용 세트(`apps`)와
                    익일부터 적용될 대기 세트(`pending`)를 함께 내려준다.

                    바인딩된 앱이 하나도 없으면 `SCREENTIME_NOT_CONFIGURED` 로 실패한다 — 첫 설정은 setup API로.
                    """)
    @ApiErrorCodes({ErrorCode.SCREENTIME_NOT_CONFIGURED, ErrorCode.LOGIN_REQUIRED,
            ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND})
    @GetMapping("/{challengeId}/my-screen-apps")
    public ApiResponse<ScreenAppsResponse> getScreenApps(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId) {
        return ApiResponse.ok(setupService.getMyScreenApps(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "내 스크린타임 앱 수정",
            description = """
                    보낸 목록으로 세트 **전체를 갈아끼운다**(부분 수정 아님). 1~10개, `packageName` 중복 불가.

                    - **적용은 항상 익일 00:00부터.** 오늘 측정분은 오늘 0시 기준 세트로 판정하므로 당일 교체로 인증을 조작할 수 없다.
                    - **변경은 월 1회** — 앵커와 동일 규칙(저장 1회 = 소진, 매월 1일 00:00 KST 리셋, 첫 설정은 미소진).
                    - 목표값(N분 이하/이상)은 정책상 변경 불가 — 이 API는 대상 앱만 다룬다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_APP, ErrorCode.SCREENTIME_NOT_CONFIGURED, ErrorCode.LOGIN_REQUIRED,
            ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND, ErrorCode.SETTING_CHANGE_LIMIT})
    @PutMapping("/{challengeId}/my-screen-apps")
    public ApiResponse<ScreenAppsUpdateResponse> updateScreenApps(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId,
            @RequestBody ScreenAppsUpdateRequest request) {
        return ApiResponse.ok(setupService.updateScreenApps(UUID.fromString(userId), challengeId, request));
    }

    // ===== 오늘 인증 =====

    @Operation(summary = "오늘 인증 결과 조회",
            description = """
                    챌린지 상세의 "오늘 인증" 카드 + **판정 결과 모달** 데이터.
                    `unacknowledgedResult`가 있으면 클라는 성공/실패 모달을 띄우고 `ack`를 호출한다.

                    `status`: `IN_PROGRESS` / `CHECKING` / `DONE` / `FAILED` / `NOT_TARGET`.
                    """)
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.NOT_CHALLENGE_MEMBER, ErrorCode.CHALLENGE_NOT_FOUND})
    @GetMapping("/{challengeId}/verifications/today")
    public ApiResponse<TodayVerificationResponse> today(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId) {
        return ApiResponse.ok(readService.today(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "수동 인증 제출",
            description = """
                    수동 인증(자체 체크) 제출. **수동 방에서만** 쓴다 — 자동 방의 실패 구제는 이의 제기가 담당한다.

                    - 별도 부정 방지 장치 없음 — **제출 즉시 인정**.
                    - **당일(KST) 마감** — 날짜가 지나면 체크·취소 전부 불가.
                    - 점수 변동은 없지만(`scoreNote=MANUAL_NO_SCORE`) **성공률·랭킹·통계에는 포함**된다.
                    """)
    @ApiErrorCodes({ErrorCode.INVALID_TARGET_DATE, ErrorCode.LOGIN_REQUIRED, ErrorCode.NOT_CHALLENGE_MEMBER,
            ErrorCode.ACCOUNT_LOCKED, ErrorCode.CHALLENGE_NOT_FOUND,
            ErrorCode.ALREADY_VERIFIED, ErrorCode.NOT_MANUAL_CHALLENGE})
    @PostMapping("/{challengeId}/verifications")
    public ApiResponse<ManualVerificationResponse> submit(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "챌린지 ID") @PathVariable UUID challengeId,
            @RequestBody ManualVerificationRequest request) {
        return ApiResponse.ok(manualService.submit(UUID.fromString(userId), challengeId, request));
    }

    // ===== 이의 제기(§8.7) — 자동 방 실패 구제 경로 =====

    @Operation(summary = "이의 제기 제출",
            description = "잠정 실패(FAILED_PROVISIONAL) 일자에 대해 1일 창 안에 본인이 제출(일자당 1회). "
                    + "사진 포함 글 또는 글. 솔로는 대상 아님.")
    @PostMapping("/{challengeId}/objections")
    public ApiResponse<ObjectionResponse> submitObjection(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID challengeId,
                                                          @RequestBody ObjectionSubmitRequest request) {
        return ApiResponse.ok(objectionService.submit(UUID.fromString(userId), challengeId, request));
    }

    @Operation(summary = "이의 제기 처리",
            description = "방장/공동 관리자. APPROVE→SUCCESS(verifiedVia=OBJECTION), "
                    + "REJECT→FAILED(OBJECTION_REJECTED, 온도 반영).")
    @PostMapping("/{challengeId}/objections/{objectionId}/decision")
    public ApiResponse<ObjectionDecisionResponse> decideObjection(@AuthenticationPrincipal String userId,
                                                                  @PathVariable UUID challengeId,
                                                                  @PathVariable UUID objectionId,
                                                                  @RequestBody ObjectionDecisionRequest request) {
        return ApiResponse.ok(objectionService.decide(
                UUID.fromString(userId), challengeId, objectionId, request));
    }

    @Operation(summary = "처리 대기함 조회",
            description = "방장/공동 관리자용. 처리 대기 중인 이의 제기(PENDING) 목록.")
    @GetMapping("/{challengeId}/pending-reviews")
    public ApiResponse<PendingReviewsResponse> pendingReviews(@AuthenticationPrincipal String userId,
                                                              @PathVariable UUID challengeId) {
        return ApiResponse.ok(pendingReviewsService.list(UUID.fromString(userId), challengeId));
    }
}
