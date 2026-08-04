package com.ruleup.ruleup_backend.intro;

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
@Tag(name = "Intro", description = "앱 인트로 · 버전 게이트(강제 업데이트)")
@RestController
@RequiredArgsConstructor
public class IntroController {

    private final IntroService introService;

    @Operation(
            summary = "앱 인트로/버전 확인",
            description = """
                    스플래시 단계에서 호출. 헤더 platform·appVersionCode를 그 플랫폼의 최소 지원 버전과 비교한다.
                    항상 200 + 공통 봉투로 응답하며, data.forceUpdate 가 true면 클라가 강제 업데이트 화면을 띄운다
                    (표시 문구는 data.minAppVersion 사용). data.termsVersions 는 현행 약관 버전 6종으로,
                    가입 동의 버전 기록과 약관 개정 시 재동의 판정에 쓴다.
                    헤더 누락/허용 외 값은 표준 400(INVALID_REQUEST) 봉투를 내려준다.
                    """
    )
    @GetMapping("/api/v1/intro")
    public ApiResponse<IntroResponse> intro(
            @Parameter(description = "클라이언트 플랫폼(ANDROID/IOS)", example = "ANDROID")
            @RequestHeader(value = "platform", required = false) String platform,

            @Parameter(description = "클라이언트 앱 버전 코드(안드로이드 versionCode, iOS 빌드 넘버)", example = "2")
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
