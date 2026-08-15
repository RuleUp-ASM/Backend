package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;

import java.util.UUID;

/**
 * 프로필 API (스펙 4.8 ~ 4.11). 모두 로그인 필요.
 * 명세 경로: /api/v1/profile (조회·수정), /api/v1/profile/image (업로드·삭제)
 */
@Tag(name = "Profile", description = "프로필 조회 · 수정 · 사진 — 검수(PENDING/APPROVED/REJECTED)에 따라 타인에게 보이는 값이 달라진다")
@SecurityRequirement(name = "bearerAuth")    // Swagger UI에서 자물쇠(토큰 입력) 표시
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final UploadRateLimiter uploadRateLimiter;

    @Operation(
            summary = "내 프로필 조회",
            description = """
                    프로필 화면용 상세 조회다. 로그인 응답의 `user` 블록보다 항목이 많다
                    (이메일·매너온도·가입일·닉네임 변경 가능 시각).

                    `nickname`·`profileImageUrl` 은 **항상 본인이 정한 값**이다(본인 화면이므로).
                    타인에게 지금 어떻게 보이는지는 `nicknameStatus`·`profileImageStatus` 로 판단한다.
                    - `APPROVED` — 타인에게도 본인 값이 보인다
                    - `PENDING` — 검수 중. 타인에게는 `tempNickname` 과 기본 프로필이 보인다
                    - `REJECTED` — 거절됨. 타인에게는 `tempNickname` 과 기본 프로필이 보이고, 변경을 유도해야 한다

                    `nicknameChangeableAfter` 는 다음 닉네임 변경이 가능해지는 시각이다(마지막 변경 +30일).
                    null 이면 아직 한 번도 바꾸지 않아 지금 바로 변경할 수 있다.

                    잠금(LOCKED) 계정도 조회할 수 있다 — 열람 전용이라 읽기는 허용된다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.ACCOUNT_BANNED})
    @GetMapping
    public ApiResponse<ProfileResponse> getMyProfile(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(profileService.getMyProfile(UUID.fromString(userId)));
    }

    @Operation(
            summary = "프로필 수정",
            description = """
                    **부분 수정**이다. 보낸 필드만 바뀌고, 빼거나 null 로 둔 필드는 그대로 유지된다.
                    현재 값과 같은 값을 보내면 변경으로 치지 않는다(닉네임 변경 주기도 소모되지 않는다).

                    **닉네임 변경은 30일에 한 번**이다. 아직 기간이 남았으면 403 이고,
                    가능해지는 시각은 조회 응답의 `nicknameChangeableAfter` 에 있다.
                    단 **검수에서 거절(REJECTED)된 닉네임을 고치는 경우는 이 제한에서 빠진다** —
                    거절당하고도 30일을 기다려야 한다면 그동안 임시 닉네임으로 지내야 하기 때문이다.

                    변경이 성공하면 이런 일이 함께 일어난다.
                    - 상태가 `PENDING` 으로 돌아가고 재검수에 들어간다. **승인 전까지 타인에게는 임시 닉네임이 보인다**
                    - 쓰던 닉네임은 "버려진" 값이 되어 **1주일간 타인이 쓸 수 없다**(사칭 방지).
                      승인 대기 중이던 값도 대상이다. 본인이 그 값으로 되돌리는 건 잠금과 무관하게 허용된다

                    `interestCategories` 는 보낸 배열로 **통째로 교체**된다(추가가 아니다). 0~6개이며 빈 배열이면 전부 해제된다.

                    `profileImageUrl` 로 URL 을 직접 지정하면 사진도 재검수 대상이 된다.
                    파일 업로드는 `POST /api/v1/profile/image` 를 쓴다.

                    잠금(LOCKED) 계정은 쓰기가 막혀 403 이다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.NICKNAME_FORMAT_INVALID,
            ErrorCode.CATEGORY_INVALID,
            ErrorCode.CATEGORY_LIMIT_EXCEEDED,
            ErrorCode.LOGIN_REQUIRED,
            ErrorCode.NICKNAME_CHANGE_LOCKED,
            ErrorCode.ACCOUNT_LOCKED,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.NICKNAME_DUPLICATED,
            ErrorCode.NICKNAME_RECENTLY_RELEASED
    })
    @PatchMapping
    public ApiResponse<ProfileResponse> updateMyProfile(@AuthenticationPrincipal String userId,
                                                        @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profileService.updateProfile(UUID.fromString(userId), request));
    }

    @Operation(
            summary = "프로필 사진 업로드",
            description = """
                    `multipart/form-data` 로 `image` 파트 하나를 보낸다(jpg 또는 png, 최대 10MB).
                    가입 직후 등록용인 `POST /api/v1/users/me/profile-image` 와 동작이 같다 — 이쪽은 프로필 화면 경로다.

                    **응답의 `status` 는 항상 `PENDING`** 이다(검수는 비동기).
                    본인 화면에는 즉시 반영되지만 승인 전까지 **타인에게는 기본 프로필**이 보인다.
                    거절되면 사진이 내려가고 알림이 간다.

                    다시 올리면 대기 중이던 검수 요청은 새 요청으로 대체된다(사용자당 대기 건은 하나만 유지).

                    남용 방지를 위해 **사용자당 1분에 10회**로 제한한다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.IMAGE_CORRUPTED,
            ErrorCode.LOGIN_REQUIRED,
            ErrorCode.ACCOUNT_LOCKED,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.IMAGE_TOO_LARGE,
            ErrorCode.IMAGE_INVALID_TYPE,
            ErrorCode.TOO_MANY_REQUESTS
    })
    // consumes 를 명시해야 문서가 이 요청을 multipart 로 그린다(기본값은 application/json 이라 파일 선택 UI가 안 나온다).
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProfileImageResponse> uploadImage(
            @AuthenticationPrincipal String userId,

            @Parameter(description = "프로필 사진 파일. jpg 또는 png, 최대 10MB.")
            @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(profileService.uploadImage(UUID.fromString(userId), image));
    }

    @Operation(
            summary = "프로필 사진 제거",
            description = """
                    등록한 사진을 내리고 기본 프로필로 돌아간다. 검수 대기 상태였어도 그대로 제거된다.

                    응답 본문은 없다 — `{"success": true, "data": null, "error": null}`.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.ACCOUNT_LOCKED, ErrorCode.ACCOUNT_BANNED})
    @DeleteMapping("/image")
    public ApiResponse<Void> deleteImage(@AuthenticationPrincipal String userId) {
        profileService.deleteImage(UUID.fromString(userId));
        return ApiResponse.ok();
    }
}
