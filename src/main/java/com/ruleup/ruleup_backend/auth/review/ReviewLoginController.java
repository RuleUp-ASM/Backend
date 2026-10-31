package com.ruleup.ruleup_backend.auth.review;

import com.ruleup.ruleup_backend.auth.dto.OAuthLoginResponse;
import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 심사 코드 로그인 (심사 계정 테크 스펙 5-2). */
@Tag(name = "Auth")
@RestController
@RequiredArgsConstructor
public class ReviewLoginController {

    private final ReviewLoginService reviewLoginService;

    @Schema(name = "ReviewLoginRequest", description = """
            심사 코드 로그인 요청. `code` 하나만 받는다.
            `deviceId`·`deviceInfo`·`installationId`·`permissions` 등 다른 필드는 보내도 무시한다(오류도 아니다).""")
    public record ReviewLoginRequest(
            @Schema(description = """
                    심사위원에게 전달한 코드(12자리). 대소문자·하이픈·공백은 구분하지 않는다 —
                    `K7QM-4XRD-9WTF`, `k7qm 4xrd 9wtf`, `K7QM4XRD9WTF` 는 같은 코드다.
                    비었거나 빠지면 400 `INVALID_REVIEW_CODE`.""",
                    example = "K7QM-4XRD-9WTF", requiredMode = Schema.RequiredMode.REQUIRED)
            String code) {}

    @Operation(
            summary = "심사 코드 로그인",
            description = """
                    스토어 심사위원이 **코드만 넣고** 전용 심사 계정으로 들어온다. 소셜 로그인·온보딩을 거치지 않는다.
                    로그인 전 단계라 토큰이 필요 없는 공개 API 다.

                    **성공 응답은 기존 회원 소셜 로그인(`isNewUser=false`)과 같은 구조다.** 받은 `accessToken`·`refreshToken` 은
                    일반 세션과 똑같이 쓰고 재발급한다. 차이는 아래뿐이다.
                    - `device` 는 항상 null — 코드 로그인은 기기 정보를 받지도 저장하지도 않는다
                    - `flushIntervalSec` 는 기기 정보가 없을 때의 기본값
                    - 탈퇴한 심사 계정이면 복원되고 `restored=true` (같은 계정 id)

                    **기기 정책이 적용되지 않는다.** 같은 기기에 심사위원 본인의 일반 계정이 있어도 그 계정의 설치·기기·세션은
                    그대로이고, 다른 기기로 다시 로그인해도 이전 세션을 끊지 않으며 세션 종료 알림도 없다.
                    심사 계정은 푸시를 받지 않는다 — 로그인 뒤 `POST /api/v1/devices` 는 200 이지만 토큰을 저장하지 않는다.

                    **심사 기능이 꺼지면**(평가 기간 종료·긴급 차단) 이 경로는 본문 없는 404 이고, 심사 계정의 토큰 재발급도
                    401 `SESSION_EXPIRED` 가 된다. 이미 받은 accessToken 은 남은 수명 동안만 쓸 수 있다.

                    실패 처리
                    - 400 `INVALID_REVIEW_CODE` — 코드가 맞지 않는다. 401 은 앱에서 토큰 재발급으로 이어지므로 쓰지 않는다.
                      입력창에 문구를 보여 주고 다시 입력받는다
                    - 403 `ACCOUNT_BANNED` — 정지된 심사 계정. 기존 로그인과 같은 처리
                    - 404 (본문 없음) — 심사 기능이 꺼져 있다. 코드를 대조하지 않으므로 틀린 코드도 404 다
                    - 429 `TOO_MANY_ATTEMPTS` — 같은 IP 에서 10분 안에 코드가 10번 틀렸다(성공은 세지 않는다).
                      맞는 코드도 거절된다. `Retry-After` 헤더와 `error.retryAfterSeconds` 에 남은 초를 싣는다 — 그동안 확인 버튼을 막는다
                    """,
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "심사 기능이 꺼져 있다 — 본문 없음", content = @Content),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                            description = "시도 초과",
                            headers = @Header(name = "Retry-After",
                                    description = "다시 시도할 수 있을 때까지 남은 초 — `error.retryAfterSeconds` 와 같다",
                                    schema = @Schema(type = "integer", example = "540")))
            }
    )
    @ApiErrorCodes({
            ErrorCode.INVALID_REVIEW_CODE,
            ErrorCode.ACCOUNT_BANNED,
            ErrorCode.TOO_MANY_ATTEMPTS
    })
    @PostMapping("/api/v1/auth/review-login")
    public ApiResponse<OAuthLoginResponse> login(@RequestBody(required = false) ReviewLoginRequest request,
                                                 HttpServletRequest http) {
        String code = (request != null) ? request.code() : null;
        return ApiResponse.ok(reviewLoginService.login(code, ReviewLoginAttemptLimiter.clientIp(http)));
    }
}
