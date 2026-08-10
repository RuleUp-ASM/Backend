package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.PublicProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{targetUserId}/profile")
@RequiredArgsConstructor
public class PublicProfileController {
    private final PublicProfileService service;

    @GetMapping
    public ApiResponse<PublicProfileResponse> get(@AuthenticationPrincipal String userId,
                                                  @PathVariable UUID targetUserId) {
        return ApiResponse.ok(service.get(UUID.fromString(userId), targetUserId));
    }
}
