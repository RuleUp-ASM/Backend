package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.dto.PublicProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 타인 프로필 조회 — 방 멤버 목록·랭킹 등에서 다른 사용자를 눌렀을 때.
 * 노출값은 "보는 사람" 기준으로 정해진다(검수·탈퇴·차단).
 */
@Tag(name = "Profile", description = "프로필 조회 · 수정 · 사진 — 검수(PENDING/APPROVED/REJECTED)에 따라 타인에게 보이는 값이 달라진다")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/{targetUserId}/profile")
@RequiredArgsConstructor
public class PublicProfileController {
    private final PublicProfileService service;

    @Operation(
            summary = "타인 프로필 조회",
            description = """
                    다른 사용자의 공개 프로필이다. 방 멤버 목록·랭킹에서 프로필을 눌렀을 때 호출한다.
                    본인 화면은 `GET /api/v1/profile` 을 쓴다(항목이 더 많다).

                    **노출값은 보는 사람 기준으로 정해진다.**
                    - 닉네임·사진은 **승인된 값만** 내려간다. 상대가 검수 중이거나 거절된 상태면
                      임시 닉네임과 기본 프로필(`profileImageUrl=null`)이 보인다
                    - 상대가 **탈퇴**했으면 `withdrawn=true` 이고 닉네임·사진은 null 이다.
                      계정이 사라지는 게 아니라 "탈퇴한 사용자"로 표시된다(기록은 남는다)
                    - 내가 **차단**한 상대면 `blocked=true` 다. 프로필 자체는 내려가므로 어떻게 그릴지는 클라이언트가 정한다

                    `completedChallengeCount` 는 완료한 챌린지 수다. 정상 완료한 방과,
                    참여 중이던 방이 삭제된 경우를 함께 센다.

                    `displayTier` 는 화면 표시용 티어다(점수는 공개하지 않는다).

                    존재하지 않는 사용자면 404 다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.ACCOUNT_BANNED, ErrorCode.USER_NOT_FOUND})
    @GetMapping
    public ApiResponse<PublicProfileResponse> get(@AuthenticationPrincipal String userId,

                                                  @Parameter(description = "조회할 사용자 ID(UUID)",
                                                          example = "0f7a3c1e-2b9d-4f6a-8c11-5d2e7b4a9c03",
                                                          required = true)
                                                  @PathVariable UUID targetUserId) {
        return ApiResponse.ok(service.get(UUID.fromString(userId), targetUserId));
    }
}
