package com.ruleup.ruleup_backend.verification.controller;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.verification.dto.SyncRequest;
import com.ruleup.ruleup_backend.verification.dto.SyncResponse;
import com.ruleup.ruleup_backend.verification.service.VerificationSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 인증 API(§3). 현재: 3.1 sync. (3.2 progress / 3.3 detail / 3.4 manual 은 다음 단계) */
@Tag(name = "Verification", description = "자동 인증 sync · 진행률 · 검증 결과")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/verifications")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationSyncService syncService;

    @Operation(summary = "인증 sync(§3.1)",
            description = "허용 신호 일괄 수집 → 오늘자 ACTIVE 챌린지 증분 평가 → 진행률 최신화. 30분 주기 호출.")
    @PostMapping("/sync")
    public ApiResponse<SyncResponse> sync(@AuthenticationPrincipal String userId,
                                          @RequestBody SyncRequest request) {
        return ApiResponse.ok(syncService.sync(UUID.fromString(userId), request));
    }
}
