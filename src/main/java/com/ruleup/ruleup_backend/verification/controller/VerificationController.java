package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.dto.SyncResponse;
import com.ruleup.ruleup_backend.verification.dto.ChallengeProgress;
import com.ruleup.ruleup_backend.verification.dto.ProgressListResponse;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroRequest;
import com.ruleup.ruleup_backend.verification.dto.VerificationIntroResponse;
import com.ruleup.ruleup_backend.verification.service.VerificationIntroService;
import com.ruleup.ruleup_backend.verification.service.VerificationReadService;
import com.ruleup.ruleup_backend.verification.service.VerificationSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** 인증 API(§3). 현재: 3.1 sync. (3.2 progress / 3.3 detail / 3.4 manual 은 다음 단계) */
@Tag(name = "Verification", description = "자동 인증 sync · 진행률 · 검증 결과")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/verifications")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationSyncService syncService;
    private final VerificationReadService readService;
    private final VerificationIntroService introService;

    @Operation(summary = "인증 sync(§3.1)",
            description = "허용 신호 일괄 수집 → 오늘자 ACTIVE 챌린지 증분 평가 → 진행률 최신화. 30분 주기 호출.")
    @PostMapping("/sync")
    public ApiResponse<SyncResponse> sync(@AuthenticationPrincipal String userId,
                                          @RequestBody SyncRequest request) {
        return ApiResponse.ok(syncService.sync(UUID.fromString(userId), request));
    }

    @Operation(summary = "Phase 0 인트로(§0.3)",
            description = "디바이스 프로필·권한 스냅샷 수신 → sync 정책(flush 주기·신호 cadence·backoff·sessionId) 회신.")
    @PostMapping("/intro")
    public ApiResponse<VerificationIntroResponse> intro(@AuthenticationPrincipal String userId,
                                                        @RequestBody VerificationIntroRequest request) {
        return ApiResponse.ok(introService.resolve(UUID.fromString(userId), request));
    }

    @Operation(summary = "진행률 일괄 조회(§3.2)",
            description = "내 챌린지 진행률을 한 번에. 홈/리스트 렌더용. status=ACTIVE(기본)/ALL. "
                    + "응답은 {asOf, challenges[]} 봉투(안드 ProgressResponse 계약).")
    @GetMapping("/progress")
    public ApiResponse<ProgressListResponse> progress(@AuthenticationPrincipal String userId,
                                                      @RequestParam(defaultValue = "ACTIVE") String status) {
        List<ChallengeProgress> challenges = readService.progress(UUID.fromString(userId), status);
        String asOf = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return ApiResponse.ok(new ProgressListResponse(asOf, challenges));
    }
}
