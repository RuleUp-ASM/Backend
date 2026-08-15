package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.dto.*;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 계정/인증 API (스펙 4.1 ~ 4.6).
 * 경로 prefix는 API 계약(§0)에 맞춰 /api/v1/auth, /api/v1/nicknames 를 사용한다.
 */
@Tag(name = "Auth", description = "소셜 로그인 · 회원가입 · 토큰 재발급/로그아웃 · 닉네임 검사")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final NicknameCheckRateLimiter nicknameCheckRateLimiter;

    /**
     * 4.1 / 4.2 소셜 로그인.
     * 명세는 /login/kakao, /login/google 로 나뉘지만 처리 로직이 동일하므로
     * {provider} path 변수 하나로 받는다. (kakao | google)
     */
    @Operation(
            summary = "소셜 로그인",
            description = """
                    카카오/구글 인가코드를 서버가 검증하고, 그 소셜 계정이 이미 가입돼 있는지에 따라 응답이 갈린다.
                    **하나의 응답 스키마 안에서 `isNewUser` 로 분기**하므로 클라이언트는 이 필드를 먼저 본다.

                    - `isNewUser=false` (기존 회원) — `accessToken`·`refreshToken`·`user`·`device`·`flushIntervalSec` 가 채워진다.
                      가입 절차 없이 바로 홈으로 진입한다. `signupToken`·`oauthProfile` 은 null 이다.
                    - `isNewUser=true` (신규) — `signupToken`·`signupTokenExpiresIn`·`oauthProfile` 만 채워진다.
                      이 시점에는 계정이 만들어지지 않는다. 온보딩을 마치고 `POST /api/v1/auth/signup` 을 호출해야 가입이 끝난다.

                    `restored=true` 는 탈퇴 후 1년 이내에 같은 소셜 계정으로 다시 로그인한 경우다.
                    신규 가입이 아니라 **기존 계정 복원**이며 이전 데이터가 그대로 살아난다.
                    이때 닉네임을 그사이 다른 사람이 가져갔다면 `user.nicknameStatus=CONFLICT` 로 내려가므로,
                    클라이언트는 닉네임 재설정 화면을 띄운다.

                    `oauthProfile` 은 온보딩 화면 **프리필 힌트**일 뿐이라 그대로 가입되지 않는다.
                    `nicknameHint` 는 중복일 수 있으니 `POST /api/v1/nicknames/check` 로 확인해야 하고,
                    `birthdayHint`·`genderHint` 는 제공자 스코프 문제로 항상 null 이다(필드는 향후를 위해 유지).

                    provider 별 차이가 하나 있다. **구글은 `redirectUri` 가 서버 등록값과 정확히 같아야 하고,
                    카카오는 null 이어도 된다**(카카오톡 간편 로그인 경로에서는 SDK가 내부 처리한다).
                    `deviceId`·`deviceInfo` 는 두 provider 모두 필수이며, 외부 호출 전에 먼저 검증해서 거절한다.

                    로그인은 **단일 활성 기기** 정책이다. 다른 기기에서 로그인하면 기존 기기의 refreshToken 이 전부 무효가 되고
                    이전 기기에는 세션 종료 알림이 나간다. 같은 기기에서 다시 로그인하는 경우에는 세션을 끊지 않는다.

                    로그인 전 단계라 토큰이 필요 없는 공개 API 다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.LOGIN_FAILED,
            ErrorCode.INVALID_REDIRECT_URI,
            ErrorCode.INVALID_DEVICE_INFO,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.INSTALLATION_ALREADY_REGISTERED,
            ErrorCode.LOGIN_PROVIDER_UNAVAILABLE
    })
    @PostMapping("/api/v1/auth/oauth/{provider}")
    public ApiResponse<OAuthLoginResponse> login(
            @Parameter(description = "소셜 로그인 제공자. kakao 또는 google (대소문자 무시).",
                    example = "kakao", required = true)
            @PathVariable String provider,
            @RequestBody OAuthLoginRequest request) {
        return ApiResponse.ok(authService.oauthLogin(parseProvider(provider), request));
    }

    /**
     * iOS 카카오 로그인용 서버 콜백(브리지).
     * 카카오는 redirect_uri를 https만 허용하므로 이 https 주소를 콘솔에 등록한다.
     * 카카오가 여기로 인가코드를 보내면, 앱 커스텀 스킴(ruleup://...)으로 302 넘겨주고
     * 앱은 그 code로 POST /login/{provider} 를 호출해 토큰을 받는다.
     */
    @Operation(
            summary = "소셜 로그인 서버 콜백 (iOS 전용 · 앱이 직접 호출하지 않음)",
            description = """
                    **클라이언트가 호출하는 API 가 아니다.** 카카오/구글이 브라우저를 통해 호출하는 리다이렉트 착지점이라
                    Swagger 의 Try it out 으로 확인할 성격이 아니고, 문서에는 흐름 파악용으로만 남겨둔다.

                    카카오 REST API 는 `redirect_uri` 로 https 만 허용하는데, iOS 앱은 Universal Links 없이는
                    그 https 착지를 직접 잡을 수 없다. 그래서 서버가 https 콜백을 대신 받아
                    인가코드를 앱 커스텀 스킴 딥링크(`ruleup://...`)로 **302 리다이렉트**해 넘겨준다.
                    앱은 그렇게 받은 `code` 로 `POST /api/v1/auth/oauth/{provider}` 를 호출해 토큰을 받는다.

                    사용자가 동의를 취소하면 `code` 대신 `error` 가 실려오고, 그 값도 그대로 딥링크로 전달된다.
                    `state` 는 앱이 CSRF 검증에 쓰라고 받은 값을 그대로 돌려준다.

                    성공 응답은 공통 봉투가 아니라 **302 + Location 헤더**다(리다이렉트이므로 본문이 없다).
                    """
    )
    @ApiErrorCodes({ErrorCode.LOGIN_FAILED})
    @GetMapping("/api/v1/auth/oauth/{provider}/callback")
    public ResponseEntity<Void> loginCallback(
            @Parameter(description = "소셜 로그인 제공자. kakao 또는 google.", example = "kakao", required = true)
            @PathVariable String provider,

            @Parameter(description = "제공자가 발급한 인가코드. 사용자가 동의를 취소하면 없을 수 있다.")
            @RequestParam(required = false) String code,

            @Parameter(description = "앱이 CSRF 검증에 쓰는 값. 받은 값을 그대로 딥링크로 되돌려준다.")
            @RequestParam(required = false) String state,

            @Parameter(description = "동의 취소·실패 시 제공자가 실어 보내는 오류 코드.", example = "access_denied")
            @RequestParam(required = false) String error) {
        parseProvider(provider);   // 알 수 없는 provider 차단
        URI appLink = authService.buildAppCallbackRedirect(provider.toLowerCase(), code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND).location(appLink).build();
    }

    @Operation(
            summary = "회원가입 완료",
            description = """
                    소셜 로그인에서 받은 `signupToken` 과 온보딩 입력을 **한 번에** 제출해 가입을 마친다.
                    성공하면 그 자리에서 앱 토큰(accessToken/refreshToken)이 발급되므로 별도 로그인 호출이 필요 없다.

                    가입은 원자적이다. 닉네임·관심사·생일·성별·약관·기기 정보 중 하나라도 검증에 실패하면
                    계정을 포함해 아무것도 만들어지지 않는다. 부분 가입 상태는 존재하지 않는다.

                    **`signupToken` 은 1회용**이다. 이미 쓴 토큰을 다시 제출하면 400 `INVALID_SIGNUP_TOKEN` 이고,
                    만료된 경우도 같은 코드다(원인을 구분하지 않는다). 이때는 소셜 로그인부터 다시 시작해야 한다.

                    입력별 규칙:
                    - `nickname` — 형식 검사 후 중복 검사. 심사(PENDING) 중인 남의 닉네임도 점유로 본다.
                      최근 누군가 변경하며 버린 닉네임은 1주일간 잠기고 409 `NICKNAME_RECENTLY_RELEASED` 로 거절된다.
                    - `interestCategories` — 0~6개(건너뛰기 허용). 중복 값은 서버가 제거한 뒤 개수를 세므로,
                      중복 때문에 상한에 걸리지는 않는다. 정의되지 않은 코드는 400 `CATEGORY_INVALID`.
                    - `birthDate` — 필수. `YYYY-MM-DD` 이고 미래일 수 없다. **만 14세 미만은 가입 불가**(법적 요구사항).
                      가입 후에는 수정할 수 없다.
                    - `gender` — 필수 필드. UI 에서 성별 입력을 건너뛰더라도 클라이언트가 `NON_BINARY` 를 보내야 한다.
                    - `agreements` — 6종 전부 보낸다. 필수 3종(이용약관·개인정보·위치기반)이 모두 `agreed=true` 여야 가입되고,
                      선택 3종(마케팅·이벤트·야간알림)은 전부 거부해도 가입된다. `version` 을 생략하면 서버 현행 버전으로 기록한다.
                    - `installationId` — 필수. 이미 다른 활성 계정이 쓰고 있는 설치면 403 `INSTALLATION_ALREADY_REGISTERED`
                      로 막고 기존 계정 로그인을 유도한다(동일 기기 다계정 차단).

                    가입 직후 상태는 항상 같다 — 티어 BRONZE·점수 10, `nicknameStatus=PENDING`, `accountStatus=ACTIVE`.
                    닉네임은 커밋 후 비동기로 검수되며, **심사 중이라고 기능이 제한되지는 않는다**.
                    검수에서 거부되면 타인에게만 임시 닉네임이 보이고 본인에게는 알림이 간다.

                    프로필 사진은 이 API 로 받지 않는다. 가입 후 accessToken 으로 `POST /api/v1/users/me/profile-image` 를 호출한다.

                    signupToken 자체가 신원 증명이라 Authorization 헤더는 필요 없는 공개 API 다.
                    """
    )
    @ApiErrorCodes({
            ErrorCode.INVALID_SIGNUP_TOKEN,
            ErrorCode.NICKNAME_FORMAT_INVALID,
            ErrorCode.CATEGORY_INVALID,
            ErrorCode.INTEREST_LIMIT_EXCEEDED,
            ErrorCode.BIRTHDATE_INVALID,
            ErrorCode.BIRTHDATE_UNDERAGE,
            ErrorCode.GENDER_REQUIRED,
            ErrorCode.REQUIRED_AGREEMENT_MISSING,
            ErrorCode.INVALID_DEVICE_INFO,
            ErrorCode.INVALID_REQUEST,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.INSTALLATION_ALREADY_REGISTERED,
            ErrorCode.NICKNAME_DUPLICATED,
            ErrorCode.NICKNAME_RECENTLY_RELEASED
    })
    @PostMapping("/api/v1/auth/signup")
    public ApiResponse<SignupResponse> signup(@RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @Operation(
            summary = "앱 토큰 재발급",
            description = """
                    accessToken 이 만료됐을 때 refreshToken 으로 새 토큰 쌍을 받는다.

                    **회전 방식**이라 accessToken 뿐 아니라 refreshToken 도 새로 발급되고, 제출한 refreshToken 은 즉시 무효가 된다.
                    클라이언트는 응답의 refreshToken 으로 저장값을 반드시 덮어써야 한다. 이전 값을 계속 쓰면 다음 호출부터 실패한다.

                    이미 무효화된 refreshToken 이 다시 제출되면 **토큰 탈취로 간주**해, 그 토큰에서 파생된 세션 전체를 폐기한다.
                    이 경우 정상 기기도 함께 로그아웃되며 재로그인이 필요하다.

                    만료·위조·폐기·탈퇴 계정 — 어느 쪽이든 401 `SESSION_EXPIRED` 하나로 내려간다.
                    클라이언트 처리는 모두 동일하게 "로그인 화면으로" 이므로 원인을 구분하지 않는다.

                    refreshToken 자체가 인증 수단이라 Authorization 헤더는 필요 없다.
                    """
    )
    @ApiErrorCodes({ErrorCode.SESSION_EXPIRED})
    @PostMapping("/api/v1/auth/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    현재 기기의 refreshToken 을 폐기한다. accessToken 은 남은 수명 동안 유효하므로 클라이언트가 함께 지운다.

                    **멱등**하다. 이미 로그아웃했거나 존재하지 않는 refreshToken 을 보내도 200 으로 성공 처리한다
                    (로그아웃이 실패해 로그인 상태에 갇히는 편이 더 나쁘다).

                    잠금(LOCKED) 계정도 로그아웃할 수 있다. 쓰기 차단 대상에서 제외돼 있다.

                    명세상 "로그인 O" 엔드포인트라 **accessToken 이 필요**하다(다른 auth API 와 달리 공개 경로가 아니다).
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiErrorCodes({ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/api/v1/auth/logout")
    public ApiResponse<Void> logout(@RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.ok();        // 200 + {success:true, data:null, error:null}
    }

    @Operation(
            summary = "닉네임 사용 가능 여부 검사",
            description = """
                    온보딩·프로필 화면에서 실시간으로 호출하는 검사 API 다. 형식·중복·잠금을 한 번에 판정한다.

                    **형식 위반도 에러가 아니라 200 + `valid:false` 로 내려간다.** 입력할 때마다 호출하는 API 라
                    에러 봉투 분기를 만들지 않기 위해서다. 400 `NICKNAME_FORMAT_INVALID` 는 가입·변경을 실제로 제출하는 시점에만 나온다.

                    응답 조합은 네 가지다.
                    - `valid:true,  available:true,  reason:null` — 사용 가능
                    - `valid:false, available:false, reason:"FORMAT"` — 형식 위반
                    - `valid:true,  available:false, reason:"DUPLICATED"` — 이미 누군가 쓰는 중(심사 중인 신청도 점유로 본다)
                    - `valid:true,  available:false, reason:"RECENTLY_RELEASED"` — 최근 변경으로 버려진 닉네임.
                      `availableAt` 에 잠금 해제 시각(ISO-8601)이 함께 실린다

                    이 검사를 통과해도 가입/변경 제출 시점에 다른 사람이 먼저 가져갈 수 있다.
                    최종 판정은 제출 시점의 409 응답이며, 클라이언트는 그 경우도 처리해야 한다.

                    무인증 공개 API 라 닉네임 열거·부하를 막기 위해 **IP 단위 호출 제한**이 걸려 있다(초과 시 429).
                    """
    )
    @ApiErrorCodes({ErrorCode.TOO_MANY_REQUESTS})
    @PostMapping("/api/v1/nicknames/check")
    public ApiResponse<NicknameAvailabilityResponse> checkNickname(@RequestBody NicknameCheckRequest request,
                                                                   HttpServletRequest httpRequest) {
        nicknameCheckRateLimiter.check(httpRequest);   // 무인증 엔드포인트 — 열거·부하 방지
        return ApiResponse.ok(authService.checkNickname(request.nickname()));
    }

    private OAuthProvider parseProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
    }
}
