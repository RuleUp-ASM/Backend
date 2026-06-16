package com.ruleup.ruleup_backend.intro;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.intro.dto.IntroResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 인트로 / 버전 게이트 API.
 *
 * <p>로그인 이전 스플래시 단계에서 호출하는 공개 엔드포인트라 {@code SecurityConfig} PUBLIC에 추가했다.
 *
 * <p><b>봉투 미사용 주의:</b> 이 엔드포인트는 다른 API와 달리 공통 봉투
 * ({@code {success,data,error}})를 쓰지 않고 {@link IntroResponse} 를 그대로 내려준다.
 * 안드로이드 {@code IntroDTO}가 바디 최상위에서 {@code devTestMsg/minAppVersion/recommendAppVersion}
 * 를 읽기 때문(특히 강제 업데이트 시 400 에러 바디를 그 형태로 역직렬화한다).
 * 그래서 강제 업데이트 분기는 {@code BusinessException}을 던지지 않고
 * {@code ResponseEntity}로 400 + flat 본문을 직접 만든다(전역 핸들러가 봉투로 감싸지 않도록).
 */
@Tag(name = "Intro", description = "앱 인트로 · 버전 게이트(강제/권장 업데이트)")
@RestController
@RequiredArgsConstructor
public class IntroController {

    private final IntroService introService;

    @Operation(
            summary = "앱 인트로/버전 확인",
            description = """
                    스플래시 단계에서 호출. 헤더 appVersionCode(클라 versionCode)를 서버 최소 지원 버전과 비교한다.
                    - 강제 업데이트 필요: 400 + { devTestMsg, minAppVersion, recommendAppVersion }
                    - 그 외: 200 + 동일 형태 본문 (클라가 recommendAppVersion으로 소프트 업데이트 유도 가능)
                    응답은 공통 봉투를 쓰지 않고 위 3개 필드를 최상위로 내려준다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정상(강제 업데이트 불필요)"),
            @ApiResponse(responseCode = "400", description = "강제 업데이트 필요(본문에 버전 정보) / 또는 헤더 누락")
    })
    @GetMapping("/intro")
    public ResponseEntity<IntroResponse> intro(
            @Parameter(description = "클라이언트 앱 버전 코드(안드로이드 versionCode)", example = "2")
            @RequestHeader(value = "appVersionCode", required = false) Integer appVersionCode
    ) {
        // 헤더 누락은 클라 구현 오류(항상 보내는 값)이므로 표준 400으로 막는다.
        if (appVersionCode == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        IntroService.IntroResult result = introService.resolve(appVersionCode);

        // 강제 업데이트 → 400 + flat 본문 (봉투 X). 그 외 → 200 + flat 본문.
        return result.forceUpdate()
                ? ResponseEntity.badRequest().body(result.body())
                : ResponseEntity.ok(result.body());
    }
}