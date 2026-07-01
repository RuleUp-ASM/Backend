package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.FallbackApprovalRequest;
import com.ruleup.ruleup_backend.verification.dto.FallbackApprovalResponse;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationRequest;
import com.ruleup.ruleup_backend.verification.dto.ManualVerificationResponse;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationRequest;
import com.ruleup.ruleup_backend.verification.dto.MemberLocationResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupRequest;
import com.ruleup.ruleup_backend.verification.dto.SetupRequirementResponse;
import com.ruleup.ruleup_backend.verification.dto.SetupResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationDetailResponse;
import com.ruleup.ruleup_backend.verification.service.VerificationApprovalService;
import com.ruleup.ruleup_backend.verification.service.VerificationManualService;
import com.ruleup.ruleup_backend.verification.service.VerificationReadService;
import com.ruleup.ruleup_backend.verification.service.VerificationSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 챌린지별 인증 API(§3.3 상세 / §3.4 수동 제출). base=/api/v1/challenges. */
@Tag(name = "Verification", description = "챌린지 인증 상세 · 수동 제출")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class VerificationChallengeController {

    private final VerificationReadService readService;
    private final VerificationManualService manualService;
    private final VerificationApprovalService approvalService;
    private final VerificationSetupService setupService;

    @Operation(summary = "챌린지 인증 여부 판단(§3.3)",
            description = "검증 결과/실패 화면용. 진행 요약·오늘 상태·실패 사유·방식별 마지막 평가·최근 로그. 참여 멤버만.")
    @GetMapping("/{challengeId}/verification")
    public ApiResponse<VerificationDetailResponse> detail(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID challengeId,
                                                          @RequestParam(defaultValue = "7") int logDays) {
        return ApiResponse.ok(readService.detail(UUID.fromString(userId), challengeId, logDays));
    }

    @Operation(summary = "수동 인증 제출(§3.4)",
            description = "정규 수동(asFallback=false)은 제출 즉시 SUCCESS. 예비 폴백(asFallback=true)은 "
                    + "PENDING_APPROVAL 로 적재되어 방장 승인 후 확정.")
    @PostMapping("/{challengeId}/verifications")
    public ApiResponse<ManualVerificationResponse> submit(@AuthenticationPrincipal String userId,
                                                          @PathVariable UUID challengeId,
                                                          @RequestBody ManualVerificationRequest request) {
        return ApiResponse.ok(manualService.submit(UUID.fromString(userId), challengeId, request));
    }

    @Operation(summary = "예비 폴백 승인/거절(§9.2)",
            description = "방장이 PENDING_APPROVAL 폴백 제출을 승인/거절. APPROVE→SUCCESS(MANUAL_FALLBACK), "
                    + "REJECT→FAILED(FALLBACK_REJECTED). 정규 수동은 승인 대상이 아니다.")
    @PostMapping("/{challengeId}/verifications/{verificationId}/approval")
    public ApiResponse<FallbackApprovalResponse> approve(@AuthenticationPrincipal String userId,
                                                         @PathVariable UUID challengeId,
                                                         @PathVariable UUID verificationId,
                                                         @RequestBody FallbackApprovalRequest request) {
        return ApiResponse.ok(approvalService.decide(
                UUID.fromString(userId), challengeId, verificationId, request));
    }

    @Operation(summary = "최초 진입 셋업 요구사항 조회(§11.4)",
            description = "가입 후 최초 진입 시, 인증을 위해 무엇을 받아/골라야 하는지 안내. "
                    + "필요 권한·앵커 필요 여부·대상앱 필요 여부 + 내 현재 셋업 상태. 참여(ACTIVE) 멤버만.")
    @GetMapping("/{challengeId}/setup")
    public ApiResponse<SetupRequirementResponse> setupRequirements(@AuthenticationPrincipal String userId,
                                                                   @PathVariable UUID challengeId) {
        return ApiResponse.ok(setupService.getRequirements(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "최초 진입 셋업(§11.4)",
            description = "권한 grant·바인딩 장소·대상 앱 등록. 모두 충족 시 READY(평가 대상 진입), 미충족 시 missing[] 반환.")
    @PostMapping("/{challengeId}/setup")
    public ApiResponse<SetupResponse> setup(@AuthenticationPrincipal String userId,
                                            @PathVariable UUID challengeId,
                                            @RequestBody SetupRequest request) {
        return ApiResponse.ok(setupService.setup(UUID.fromString(userId), challengeId, request));
    }

    @Operation(summary = "내 인증 장소(앵커) 조회(§11.5)",
            description = "내가 바인딩한 앵커 목록을 반환. 위치 셋업/수정 화면 재진입 시 지도에 기존 핀 복원용. "
                    + "앵커가 하나도 없으면 GEOFENCE_NOT_CONFIGURED 실패 응답(success=false). 참여(ACTIVE) 멤버만.")
    @GetMapping("/{challengeId}/my-location")
    public ApiResponse<MemberLocationResponse> getLocation(@AuthenticationPrincipal String userId,
                                                           @PathVariable UUID challengeId) {
        return ApiResponse.ok(setupService.getMyLocation(UUID.fromString(userId), challengeId));
    }

    @Operation(summary = "내 인증 장소 수정(§11.5)",
            description = "본인 앵커 교체. 최근 변경 후 쿨다운 동안은 재변경 불가. 즉시 적용.")
    @PutMapping("/{challengeId}/my-location")
    public ApiResponse<MemberLocationResponse> updateLocation(@AuthenticationPrincipal String userId,
                                                              @PathVariable UUID challengeId,
                                                              @RequestBody MemberLocationRequest request) {
        return ApiResponse.ok(setupService.updateLocation(UUID.fromString(userId), challengeId, request));
    }
}
