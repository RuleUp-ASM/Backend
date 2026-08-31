package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.ProfileUpdateResponse;
import com.ruleup.ruleup_backend.profile.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 프로필 편집 — 마이페이지 5-2 #8. 이 모듈에서 <b>유일한 쓰기 API</b> 다.
 *
 * <p>경로가 {@code /api/v1/profile} 이 아니라 {@code /api/v1/users/me/profile} 인 것은 계약이다 —
 * 조회({@code GET /api/v1/users/me})와 같은 자원 아래 놓인다.
 */
@Tag(name = "Me", description = "마이 홈 · 캘린더 · 통계 · 티어 · 이의 · 프로필 편집")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class MyProfileController {

    private final ProfileService profileService;

    @Operation(
            summary = "프로필 편집",
            description = """
                    닉네임 · 관심 분야 · 사진 삭제. 보낸 필드만 바뀌고 뺀 필드는 유지된다.

                    **닉네임과 사진은 통합 1개월 잠금**이다. 둘 중 하나라도 바꾸는 저장을 하면
                    그 시점부터 두 항목 모두 1개월간 변경할 수 없고, 해제 시각이 `profileLockedUntil` 로 내려간다.
                    항목별로 따로 잠그면 닉네임 → 사진 → 닉네임 순으로 사실상 무제한 변경이 되기 때문이다.

                    **한 번의 저장에서 동시 수정이 가능하다.** 사진은 `POST /api/v1/users/me/profile-image`
                    (업로드+등록)를 먼저 호출하는데, 그 직후 10분 안의 닉네임 변경은 같은 저장 세션으로 묶여
                    잠금에 걸리지 않는다.

                    **모더레이션 거부에 따른 재수정은 잠금에서 제외**된다 —
                    `nicknameStatus`·`profileImageStatus` 가 `REJECTED` 인 동안의 재제출은 항상 허용한다.
                    거부 횟수만으로 수정을 제한하지도 않는다(구 `MODERATION_LOCKED` 폐기).

                    **관심 분야는 잠금 예외**로 언제든 바꿀 수 있다. 0~6개이며 보낸 배열로 통째로 교체된다.

                    생일·성별은 가입 후 수정 불가 항목이라 이 API 에 없다.
                    잠금(LOCKED) 계정은 쓰기가 막혀 403 이다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.PROFILE_CHANGE_LOCKED,
            ErrorCode.NICKNAME_FORMAT_INVALID,
            ErrorCode.NICKNAME_DUPLICATED,
            ErrorCode.INTEREST_LIMIT_EXCEEDED,
            ErrorCode.CATEGORY_INVALID,
            ErrorCode.ACCOUNT_LOCKED,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.LOGIN_REQUIRED
    })
    @PatchMapping("/api/v1/users/me/profile")
    public ApiResponse<ProfileUpdateResponse> updateMyProfile(@AuthenticationPrincipal String userId,
                                                              @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profileService.updateProfile(UUID.fromString(userId), request));
    }
}
