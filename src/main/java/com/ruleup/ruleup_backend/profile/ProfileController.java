package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;

import java.util.UUID;

/**
 * 프로필 API (스펙 4.8 ~ 4.11). 모두 로그인 필요.
 * 명세 경로: /api/v1/profile (조회·수정), /api/v1/profile/image (업로드·삭제)
 */
@Tag(name = "Profile", description = "내 프로필 조회 · 수정 · 사진")
@SecurityRequirement(name = "bearerAuth")    // Swagger UI에서 자물쇠(토큰 입력) 표시
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final UploadRateLimiter uploadRateLimiter;

    @Operation(summary = "내 프로필 조회")
    @GetMapping
    public ApiResponse<ProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(profileService.getMyProfile(UUID.fromString(userId)));
    }

    @Operation(summary = "프로필 수정", description = "모든 필드 선택(null이면 변경 안 함)")
    @PatchMapping
    public ApiResponse<ProfileResponse> updateMyProfile(@AuthenticationPrincipal String userId,
                                                        @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profileService.updateProfile(UUID.fromString(userId), request));
    }

    @Operation(summary = "프로필 사진 업로드", description = "jpg/png, 최대 10MB")
    @PostMapping("/image")
    public ApiResponse<ProfileImageResponse> uploadImage(@AuthenticationPrincipal String userId,
                                                         @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(profileService.uploadImage(UUID.fromString(userId), image));
    }

    @Operation(summary = "프로필 사진 제거", description = "기본 프로필로 복귀")
    @DeleteMapping("/image")
    public ApiResponse<Void> deleteImage(@AuthenticationPrincipal String userId) {
        profileService.deleteImage(UUID.fromString(userId));
        return ApiResponse.ok();
    }
}