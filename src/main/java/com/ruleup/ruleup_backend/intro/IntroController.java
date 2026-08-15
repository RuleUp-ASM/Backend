package com.ruleup.ruleup_backend.intro;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.intro.dto.IntroResponse;
import com.ruleup.ruleup_backend.user.domain.Platform;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 인트로 / 버전 게이트 API.
 *
 * <p>로그인 이전 스플래시 단계에서 호출하는 공개 엔드포인트(SecurityConfig PUBLIC에 등록).
 * 첫 설치·로그아웃·토큰 만료처럼 토큰이 없는 상태가 흔하고, 강제 업데이트 판정은 로그인보다
 * 먼저 이뤄져야 하므로 인증을 요구하지 않는다.
 *
 * <p>다른 API와 동일하게 공통 봉투({@code ApiResponse})로 응답한다.
 * 강제 업데이트도 별도 400 에러로 내리지 않고, 항상 200 + {@code data.forceUpdate} 플래그로 알린다.
 * → 클라는 기존 {@code BaseResponse<T>} + {@code getOrThrow()} 흐름을 그대로 재사용하면 된다.
 */
@Tag(name = "Intro", description = "앱 인트로 · 버전 게이트 — 로그인 전 스플래시에서 호출(토큰 불필요)")
@RestController
@RequiredArgsConstructor
public class IntroController {

    private final IntroService introService;

    @Operation(
            summary = "앱 인트로 / 버전 확인",
            description = """
                    앱을 켜면 **가장 먼저 호출하는 API** 다. 로그인 이전 단계라 토큰이 필요 없다
                    (첫 설치·로그아웃·토큰 만료처럼 토큰이 없는 상태가 흔하고, 강제 업데이트 판정은 로그인보다 먼저 이뤄져야 한다).

                    헤더로 받은 `platform`·`appVersionCode` 를 **그 플랫폼의** 최소 지원 버전과 비교한다.
                    같은 버전 코드라도 플랫폼에 따라 판정이 갈리므로 `platform` 이 필수다.

                    **강제 업데이트도 에러가 아니라 200 + 플래그**로 내려간다. `data.forceUpdate` 가 true 면
                    클라이언트가 강제 업데이트 화면을 띄우고(문구는 `data.minAppVersion` 사용) 그 아래 흐름을 막는다.
                    별도의 에러 분기를 만들 필요가 없도록 한 계약이다. 업데이트 정책에 예외가 없어 "권장 버전"은 내려주지 않는다.

                    `data.termsVersions` 는 현행 약관 버전 6종이다. **클라이언트가 약관 버전을 하드코딩하지 않게 하려는 것**으로,
                    가입 시 동의 버전 기록과 약관 개정 시 재동의 판정(`GET /api/v1/users/me` 의 저장 버전과 비교)에 쓴다.

                    설정값이 빈 문자열이면 null 로 내려간다(클라이언트의 폴백 처리가 동작하도록).
                    헤더 누락이나 허용 외 값은 400 `INVALID_REQUEST` 다 — 항상 보내야 하는 값이라 클라이언트 구현 오류로 본다.
                    """
    )
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST})
    @GetMapping("/api/v1/intro")
    public ApiResponse<IntroResponse> intro(
            @Parameter(description = "클라이언트 플랫폼(ANDROID/IOS). 누락·허용 외 값은 400.",
                    example = "ANDROID", required = true)
            @RequestHeader(value = "platform", required = false) String platform,

            @Parameter(description = "클라이언트 앱 버전 코드(안드로이드 versionCode, iOS 빌드 넘버). 누락은 400.",
                    example = "2", required = true)
            @RequestHeader(value = "appVersionCode", required = false) Integer appVersionCode
    ) {
        // 헤더 누락은 클라 구현 오류(항상 보내는 값) → 표준 봉투 400으로 막는다.
        if (appVersionCode == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        // platform 없이는 플랫폼별 최소 버전을 고를 수 없어 강제 업데이트 판정 자체가 불가하다.
        return ApiResponse.ok(introService.resolve(parsePlatform(platform), appVersionCode));
    }

    private Platform parsePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        try {
            return Platform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
