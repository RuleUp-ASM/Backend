package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
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
 * 계정 잠금(LOCKED) 상태의 <b>열람 전용</b> 강제 — 회원 정책 §7.2.
 *
 * <p>잠금 계정은 로그인·조회는 되지만 "열람과 문의를 제외한 모든 행동"이 차단된다:
 * 챌린지 참여·생성·재입장, 인증 데이터 제출, 이의 제기, 신고, 프로필 편집 등.
 * 구현은 <b>상태 변경 메서드(POST/PUT/PATCH/DELETE)를 403 ACCOUNT_LOCKED 로 막고</b>,
 * 조회(GET/HEAD/OPTIONS)는 모두 통과시키는 방식이다.
 *
 * <p>예외적으로 허용하는 쓰기 (정책상 잠금 중에도 가능해야 하는 동작):
 * <ul>
 *   <li>{@code POST /api/v1/auth/logout} — 로그아웃</li>
 *   <li>{@code DELETE /api/v1/users/me} — 탈퇴 (§7.5 잠금 중에도 탈퇴는 허용)</li>
 * </ul>
 * 로그인·토큰 재발급은 애초에 공개 경로라 인증 컨텍스트가 없어 이 필터를 그냥 통과한다.
 *
 * <p>상태를 JWT 클레임이 아니라 DB에서 읽는 이유: 잠금은 운영자가 언제든 걸 수 있는데
 * 클레임에 넣으면 액세스 토큰 수명(30분)만큼 우회가 생긴다. 조회 부하를 줄이려고
 * <b>쓰기 요청에서만</b> 확인한다.
 */
@RequiredArgsConstructor
public class LockedAccountFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> READ_ONLY_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBlockedWhileLocked(request) && isLocked(currentUserId())) {
            writeAccountLocked(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 잠금 시 막아야 하는 요청인지 — 상태 변경이면서 허용 목록에 없는 경우. */
    private boolean isBlockedWhileLocked(HttpServletRequest request) {
        if (READ_ONLY_METHODS.contains(request.getMethod())) return false;
        return !isAllowedWhileLocked(request);
    }

    private boolean isAllowedWhileLocked(HttpServletRequest request) {
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

    private boolean isLocked(UUID userId) {
        return userId != null && userRepository.findById(userId).map(User::isLocked).orElse(false);
    }

    private void writeAccountLocked(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.ACCOUNT_LOCKED;
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(ErrorResponse.of(code))));
    }
}
