package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/me")
    public ApiResponse<ProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(profileService.getMyProfile(UUID.fromString(userId)));
    }

    @PatchMapping("/me")
    public ApiResponse<ProfileResponse> updateMyProfile(@AuthenticationPrincipal String userId,
                                                        @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profileService.updateProfile(UUID.fromString(userId), request));
    }

    @PostMapping("/image")
    public ApiResponse<ProfileImageResponse> uploadImage(@AuthenticationPrincipal String userId,
                                                         @RequestPart("image") MultipartFile image) {
        return ApiResponse.ok(profileService.uploadImage(UUID.fromString(userId), image));
    }

    @DeleteMapping("/image")
    public ApiResponse<Void> deleteImage(@AuthenticationPrincipal String userId) {
        profileService.deleteImage(UUID.fromString(userId));
        return ApiResponse.ok();        // 200 + 봉투
    }
}