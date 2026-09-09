package com.ruleup.ruleup_backend.admin.auth;

import com.ruleup.ruleup_backend.common.docs.ApiErrorCodes;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 운영자 콘솔 진입 — 백오피스 공통 5-2-1 B.
 *
 * <p>로그인만 공개 경로다. 나머지는 다른 백오피스 API 와 같은 통제를 받는다.
 */
@Tag(name = "Admin Auth", description = "운영자 콘솔 진입 — 비밀번호 하나. 값은 서버 env 에만 둔다")
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService service;

    @Schema(name = "AdminLoginRequest")
    public record LoginRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String passcode) {}

    @Operation(summary = "콘솔 로그인", description = """
            **비밀번호 하나만 받는다.** 값은 서버 env 에만 두며 프론트 env 에 넣으면 번들에 실려
            접근 통제가 성립하지 않는다.

            성공하면 **설정된 운영자 계정의 액세스 토큰**을 준다 — 비밀번호에는 신원이 없지만
            감사 로그는 조작자를 남겨야 하기 때문이다. 이후 모든 요청은 이 토큰을 그대로 쓴다.

            실패는 401 `INVALID_PASSCODE`, 시도 과다는 429 `TOO_MANY_ATTEMPTS` 이며 **둘 다
            감사 로그에 `DENIED` 로 남는다.**

            ⚠️ 이 절은 임시다 — 운영자 계정 인증 방식(공통 오픈 이슈 #2)이 정해지면 대체된다.
            """)
    @ApiErrorCodes({ErrorCode.INVALID_PASSCODE, ErrorCode.TOO_MANY_ATTEMPTS})
    @PostMapping("/login")
    public ApiResponse<AdminAuthService.Session> login(@RequestBody LoginRequest request,
                                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(service.login(request == null ? null : request.passcode(),
                clientKey(servletRequest)));
    }

    @Operation(summary = "세션 확인", description = """
            새로고침 후 토큰이 아직 유효한지 확인한다. 여기까지 응답이 왔다는 것은 접근 통제를
            통과했다는 뜻이므로, 콘솔은 이 호출 하나로 진입 여부를 정할 수 있다.
            """)
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @GetMapping("/session")
    public ApiResponse<AdminAuthService.Session> session(@AuthenticationPrincipal String userId) {
        return ApiResponse.ok(service.session(UUID.fromString(userId)));
    }

    @Operation(summary = "로그아웃", description = """
            **토큰을 버리는 것이 로그아웃이다.** 콘솔 토큰은 상태를 두지 않는 짧은 수명의 액세스
            토큰이라 서버가 회수할 대상이 없다 — 유저 앱처럼 리프레시 토큰을 폐기하는 경로가
            없으므로, 여기서 성공을 돌려주고 클라이언트가 저장된 토큰을 지운다.
            """)
    @ApiErrorCodes({ErrorCode.ADMIN_FORBIDDEN, ErrorCode.LOGIN_REQUIRED})
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok(null);
    }

    /**
     * 시도 제한의 단위. 프록시 뒤라 {@code X-Forwarded-For} 가 있으면 그 앞단 주소를 쓴다 —
     * 없으면 전부 같은 키가 되어 한 사람의 오타가 다른 운영자를 잠근다.
     */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
