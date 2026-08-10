package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReportController {
    private final BlacklistService service;

    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportDtos.CreateResponse> report(@AuthenticationPrincipal String userId,
                                                         @RequestBody ReportDtos.CreateRequest request) {
        return ApiResponse.ok(service.report(UUID.fromString(userId), request));
    }

    @GetMapping("/api/v1/users/me/blacklist")
    public ApiResponse<ReportDtos.BlacklistResponse> list(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.list(UUID.fromString(userId)));
    }

    @DeleteMapping("/api/v1/users/me/blacklist/users/{blockedUserId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockUser(@AuthenticationPrincipal String userId,
                                                              @PathVariable UUID blockedUserId) {
        return ApiResponse.ok(service.unblockUser(UUID.fromString(userId), blockedUserId));
    }

    @DeleteMapping("/api/v1/users/me/blacklist/challenges/{challengeId}")
    public ApiResponse<ReportDtos.DeleteResponse> unblockChallenge(@AuthenticationPrincipal String userId,
                                                                   @PathVariable UUID challengeId) {
        return ApiResponse.ok(service.unblockChallenge(UUID.fromString(userId), challengeId));
    }
}
