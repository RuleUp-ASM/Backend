package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * 제재 상태(LOCKED / BANNED)에 따른 접근 제한 — 회원 정책 §7.
 *
 * <p><b>LOCKED(잠금)은 열람 전용</b>이다. 조회(GET/HEAD/OPTIONS)는 통과시키고 상태 변경
 * (POST/PUT/PATCH/DELETE)만 403 ACCOUNT_LOCKED 로 막는다 — 챌린지 참여·생성, 인증 데이터 제출,
 * 이의 제기, 신고, 프로필 편집 등.
 *
 * <p><b>BANNED(영구 정지)는 조회까지 전부</b> 403 ACCOUNT_BANNED 다. 정지 계정은 원래 로그인
 * 자체가 막히지만(토큰을 못 받는다), 탈퇴 후 같은 기기에서 재가입해 정지를 승계한 계정은
 * 가입 응답으로 토큰을 받는다(회원 정책 §6). 그 토큰으로는 아무것도 못 하게 여기서 막는다.
 *
 * <p>두 상태 모두 예외적으로 허용하는 요청:
 * <ul>
 *   <li>{@code POST /api/v1/auth/logout} — 세션을 못 끊으면 로그인 상태에 갇힌다</li>
 *   <li>{@code DELETE /api/v1/users/me} — 탈퇴. 정지 계정도 계정을 지울 수 있어야 한다(§7.5).
 *       탈퇴해도 제재는 설치 이력으로 따라오므로 세탁 수단이 되지 않는다</li>
 * </ul>
 * 로그인·토큰 재발급은 공개 경로라 인증 컨텍스트가 없어 이 필터를 그냥 통과한다.
 *
 * <p>상태를 JWT 클레임이 아니라 DB 에서 읽는 이유: 제재는 운영자가 언제든 걸 수 있는데
 * 클레임에 넣으면 액세스 토큰 수명(30분)만큼 우회가 생긴다. 대신 엔티티 전체가 아니라
 * status 컬럼만 읽어(projection) 요청당 비용을 PK 조회 1건으로 묶는다.
 */
@RequiredArgsConstructor
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> READ_ONLY_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ErrorCode blocked = blockedReason(request);
        if (blocked != null) {
            writeError(response, blocked);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 막아야 하면 그 사유 코드를, 통과시켜야 하면 null 을 준다. */
    private ErrorCode blockedReason(HttpServletRequest request) {
        UUID userId = currentUserId();
        if (userId == null) return null;                 // 미인증(공개 경로) — 그대로 통과
        if (isAlwaysAllowed(request)) return null;       // 로그아웃·탈퇴는 제재 중에도 허용

        UserStatus status = userRepository.findStatusById(userId).orElse(null);
        if (status == UserStatus.BANNED) return ErrorCode.ACCOUNT_BANNED;   // 조회 포함 전면 차단
        if (status == UserStatus.LOCKED && !isReadOnly(request)) return ErrorCode.ACCOUNT_LOCKED;
        return null;
    }

    private boolean isReadOnly(HttpServletRequest request) {
        return READ_ONLY_METHODS.contains(request.getMethod());
    }

    private boolean isAlwaysAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (HttpMethod.POST.name().equals(method) && "/api/v1/auth/logout".equals(path)) return true;
        return HttpMethod.DELETE.name().equals(method) && "/api/v1/users/me".equals(path);
    }

    /** 인증 컨텍스트의 userId (미인증이면 null — 공개 경로는 그대로 통과). */
    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String principal))
            return null;
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(ErrorResponse.of(code))));
    }
}
