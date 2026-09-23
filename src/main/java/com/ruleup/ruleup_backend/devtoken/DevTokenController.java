package com.ruleup.ruleup_backend.devtoken;

import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 개발용 토큰 발급 — 소셜 로그인을 거치지 않고 테스트용 AT/RT 를 받는다.
 *
 * <p>방어가 네 겹이고 <b>순서가 중요하다</b>.
 * <ol>
 *   <li>{@code @Profile("!prod")} — 빈 등록 자체를 막는다. 설정값 토글은 오설정 한 줄로 prod 에서 켜진다</li>
 *   <li>{@code X-Dev-Secret} 헤더 검증 — 스테이징이 외부에 열려 있을 때의 2차 방어</li>
 *   <li>불일치 시 <b>404</b> — 401 을 내리면 경로가 존재한다는 사실이 새어나간다.
 *       404 로 미배포와 구분되지 않게 한다</li>
 *   <li>발급 전건 감사 로그</li>
 * </ol>
 *
 * <p>{@code @Hidden} 으로 스웨거에서도 뺀다. prod 에서는 빈이 없어 자연히 빠지지만, 개발 문서에
 * 인증 우회로가 목록으로 실려 있으면 그 자체가 안내가 된다.
 */
@Hidden
@RestController
@Profile("!prod")
@RequiredArgsConstructor
public class DevTokenController {

    private final DevTokenService devTokenService;

    /** 비어 있으면 어떤 헤더로도 통과하지 못한다 — 미설정이 곧 비활성이다. */
    @Value("${app.dev-tokens.secret:}")
    private String secret;

    @PostMapping("/api/v1/dev/tokens")
    public ApiResponse<DevTokenDtos.Response> issue(
            @RequestHeader(value = "X-Dev-Secret", required = false) String provided,
            @RequestBody(required = false) DevTokenDtos.Request request,
            HttpServletResponse response) {
        requireSecret(provided);
        return ApiResponse.ok(devTokenService.issue(request));
    }

    /**
     * 시크릿 불일치는 <b>본문 없는 404</b> 다. 에러 코드조차 내리지 않는다 —
     * {@code DEV_SECRET_MISMATCH} 같은 코드가 내려가면 그것만으로 경로의 존재가 드러난다.
     */
    private void requireSecret(String provided) {
        if (secret == null || secret.isBlank() || !secret.equals(provided))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
