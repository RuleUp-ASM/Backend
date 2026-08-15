package com.ruleup.ruleup_backend.user;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.image.UploadRateLimiter;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.profile.ProfileService;
import com.ruleup.ruleup_backend.profile.dto.ProfileImageResponse;
import com.ruleup.ruleup_backend.user.dto.UserMeResponse;
import com.ruleup.ruleup_backend.user.dto.WithdrawRequest;
import com.ruleup.ruleup_backend.user.dto.WithdrawResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 회원 계정 API — 내 프로필 조회 / 회원 탈퇴 (소셜 로그인·온보딩 모듈 계약 #9·#10).
 */
@Tag(name = "Account", description = "내 프로필 조회 · 프로필 사진 등록 · 회원 탈퇴")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class UserAccountController {

    private final UserAccountService userAccountService;
    private final ProfileService profileService;
    private final UploadRateLimiter uploadRateLimiter;

    @Operation(
            summary = "내 프로필 조회",
            description = """
                    로그인 응답의 `user` 블록에 **본인만 볼 수 있는 항목**(생일·성별·약관 동의 상태)을 더해 내려준다.
                    타인이 보는 공개 프로필과는 다른 API 다.

                    `agreements` 는 약관 6종의 **현재 상태**다. 동의 이력은 append-only 로 쌓이고 여기에는 각 약관의 최신 1건만 나온다.
                    키는 가입 요청과 같은 `termsOfService`·`privacyPolicy`·`locationService`·`marketing`·`event`·`nightPush` 이고,
                    각 항목은 `{ agreed, version, agreedAt }` 형태다.
                    저장된 `version` 을 `GET /api/v1/intro` 의 현행 버전과 비교하면 재동의가 필요한지 판단할 수 있다.
                    한 번도 기록이 없는 약관은 키 자체가 빠진다.

                    `birthDate`·`gender` 는 온보딩에서 수집하지 않았다면 null 이다.

                    잠금(LOCKED) 계정도 조회할 수 있다 — 열람 전용이라 마이페이지·본인 기록 확인은 허용된다.
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED, ErrorCode.ACCOUNT_BANNED})
    @GetMapping("/api/v1/users/me")
    public ApiResponse<UserMeResponse> me(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(userAccountService.me(UUID.fromString(userId)));
    }

    @Operation(
            summary = "프로필 사진 등록",
            description = """
                    가입 API 에서는 사진을 받지 않는다. 가입을 마친 뒤 accessToken 으로 이 API 를 따로 호출한다.
                    `multipart/form-data` 로 `image` 파트 하나를 보낸다(jpg 또는 png, 최대 10MB).

                    **응답의 `status` 는 항상 `PENDING`** 이다. 업로드 직후 자동 검수가 비동기로 돌기 때문이다.
                    본인 화면에는 방금 올린 사진이 바로 보이지만, 승인 전까지 **타인에게는 기본 프로필**이 보인다.
                    검수에서 거부되면 사진이 내려가고 알림이 간다.

                    다시 올리면 이전 검수 요청은 새 요청으로 대체된다. 같은 사용자에 대해 대기 중인 검수는 한 건만 유지한다.

                    남용 방지를 위해 **사용자당 1분에 10회**로 제한한다(초과 시 429).
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
    @PostMapping(value = "/api/v1/users/me/profile-image",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProfileImageResponse> uploadProfileImage(
            @AuthenticationPrincipal String userId,

            @Parameter(description = "프로필 사진 파일. jpg 또는 png, 최대 10MB.")
            @RequestPart("image") MultipartFile image) {
        uploadRateLimiter.check(userId);
        return ApiResponse.ok(profileService.uploadImage(UUID.fromString(userId), image));
    }

    @Operation(
            summary = "회원 탈퇴",
            description = """
                    실수 방지를 위해 확인 문구를 함께 받는다. **`confirmPhrase` 는 정확히 `"탈퇴할게요"`** 여야 하고,
                    다르면 400 이며 아무것도 지워지지 않는다.

                    **소프트 탈퇴**다. 계정은 WITHDRAWN 으로 바뀌고 다음이 함께 일어난다.
                    - 모든 refreshToken 폐기 → 전 기기 세션 종료
                    - 기기(deviceId) 연결 해제 → 단일 활성 기기 판정에서 풀린다
                      (설치 ID 는 계속 묶여 있다 — 아래 참고)
                    - 닉네임 점유 해제 → 다른 사람이 곧바로 쓸 수 있다
                    - **참여 중인 모든 챌린지에서 탈퇴**(방장이던 방은 봇 방장으로 전환) —
                      남겨두면 인증하지 않는 유령 멤버가 남의 방 정원을 차지한다

                    **멱등**하다. 이미 탈퇴한 계정이 다시 호출해도 200 으로 같은 응답을 준다.

                    응답의 `archiveExpiresAt` 은 개인정보 아카이브 파기 예정 시각(탈퇴 +1년)이다.
                    그 전에 **같은 소셜 계정으로 다시 오면 복원**되어 기존 기록이 살아난다.
                    복귀는 로그인이 아니라 **회원가입 흐름**으로 들어온다 —
                    소셜 로그인이 `isNewUser=true`·`returningUser=true` 를 주고,
                    `POST /api/v1/auth/signup` 이 입력 없이 이전 계정을 되살려 로그인시킨다.

                    **정지(BANNED)·잠금(LOCKED) 계정도 탈퇴할 수 있다.** 대신 제재가 따라온다 —
                    탈퇴 직전 상태가 계정에 남아 복원 시 그대로 되살아나고, 정지 상태로 탈퇴했다면 재로그인 자체가 403 이다.

                    **설치(installationId)는 탈퇴해도 풀리지 않는다.** 그래서 같은 기기에서 *다른* 소셜 계정으로
                    새로 가입하는 것은 403 `INSTALLATION_ALREADY_REGISTERED` 로 막힌다 —
                    소셜만 바꿔 점수·제재를 리셋하는 우회로를 닫기 위해서다.
                    돌아오려면 원래 소셜 계정으로 로그인해야 하고, 그 경로는 복원이라 기록이 그대로 따라온다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.CONFIRM_PHRASE_MISMATCH,
            ErrorCode.LOGIN_REQUIRED
    })
    @DeleteMapping("/api/v1/users/me")
    public ApiResponse<WithdrawResponse> withdraw(@AuthenticationPrincipal String userId,
                                                  @RequestBody WithdrawRequest request) {
        return ApiResponse.ok(userAccountService.withdraw(UUID.fromString(userId), request.confirmPhrase()));
    }
}
