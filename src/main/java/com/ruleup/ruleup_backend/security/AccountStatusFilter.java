package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import com.ruleup.ruleup_backend.sanction.SanctionService;
import com.ruleup.ruleup_backend.sanction.domain.FeatureCode;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import com.ruleup.ruleup_backend.sanction.domain.SanctionType;
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
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 계정 상태 게이트 — 온보딩 테크 스펙 5-6.
 *
 * <p>판정은 두 단계다. {@code users.status} 를 먼저 읽고, <b>SUSPENDED 일 때만</b> 활성
 * {@code sanctions} 를 조회해 차단 범위를 정한다. {@code WITHDRAWN} 은 sanctions 를 보지 않고
 * <b>전역 차단</b>한다 — 허용 범위가 "없음"이다(온보딩 5-6). 정상 사용자는 두 번째 조회를 하지 않으므로
 * 일반 요청의 게이트 비용은 status 한 번을 보는 수준이다 — 상태값을 세분화하지 않고도 제재
 * 종류를 분리할 수 있는 이유가 이것이다.
 *
 * <table>
 *   <tr><th>제재</th><th>차단 범위</th><th>응답</th></tr>
 *   <tr><td>FEATURE_SUSPENSION</td><td>{@code featureCode} 에 적힌 API 만</td><td>403 ACCOUNT_SUSPENDED</td></tr>
 *   <tr><td>LOCK</td><td>열람 전용 — 상태 변경만</td><td>403 ACCOUNT_LOCKED</td></tr>
 *   <tr><td>BAN</td><td>조회까지 전부</td><td>403 ACCOUNT_BANNED</td></tr>
 * </table>
 *
 * <p><b>화이트리스트가 안전장치다.</b> 잠금 사유와 해제일을 볼 수 없으면 사용자는 자기 상황을
 * 알 방법이 없고, 제재 고지는 알림함에 쌓인다. 그래서 제재 이력·알림함·동의 상태 조회와
 * 로그아웃·탈퇴는 제재 중에도 열어 둔다.
 *
 * <p>상태를 JWT 클레임이 아니라 DB 에서 읽는 이유: 제재는 운영자가 언제든 걸 수 있는데 클레임에
 * 넣으면 액세스 토큰 수명(30분)만큼 우회가 생긴다.
 */
@RequiredArgsConstructor
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> READ_ONLY_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    /**
     * 제재 중에도 반드시 열려야 하는 경로.
     *
     * <p>앞의 둘은 갇힘 방지다 — 세션을 못 끊거나 계정을 못 지우면 사용자가 빠져나올 수 없다.
     * 뒤의 셋은 상황 인지다 — 제재 사유·해제일과 고지를 볼 수 없으면 왜 막혔는지 알 수 없다.
     */
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "POST /api/v1/auth/logout",
            "DELETE /api/v1/users/me",
            "GET /api/v1/users/me/sanctions",
            "GET /api/v1/notifications",
            "GET /api/v1/users/me/agreements");

    /**
     * 탈퇴 계정에도 남겨 두는 경로 — <b>여기만</b> 열고 나머지는 전부 막는다.
     *
     * <p>제재 화이트리스트보다 훨씬 좁다. 제재는 "갇히지 않게" 열어 두는 것이지만 탈퇴는 이미
     * 나간 계정이라 볼 것이 없다 — 알림함·제재 이력·동의 상태는 모두 닫는다. 남는 둘은
     * 세션을 끊는 경로와, 멱등해야 하는 탈퇴 재요청뿐이다.
     */
    private static final Set<String> ALLOWED_WHEN_WITHDRAWN = Set.of(
            "POST /api/v1/auth/logout",
            "DELETE /api/v1/users/me");

    private final UserRepository userRepository;
    private final SanctionService sanctionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Blocked blocked = blockedReason(request);
        if (blocked != null) {
            writeError(response, blocked);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 막아야 하면 사유를, 통과시켜야 하면 null 을 준다. */
    private Blocked blockedReason(HttpServletRequest request) {
        UUID userId = currentUserId();
        if (userId == null) return null;                 // 미인증(공개 경로) — 그대로 통과

        UserStatus status = userRepository.findStatusById(userId).orElse(null);

        // 탈퇴는 화이트리스트보다 먼저 본다. 탈퇴 시 RT 를 전부 revoke 해도 <b>이미 발급된 AT 는
        // 최대 30분 살아 있으므로</b>, 그 창에서 참여·인증 같은 쓰기 API 가 그대로 통한다.
        // 개별 API 가 각자 deletedAt 을 보게 두면 빠뜨린 API 가 곧 구멍이 되므로 여기서 전역으로 막는다.
        if (status == UserStatus.WITHDRAWN) {
            // 401 이다 — 탈퇴 계정에 남은 토큰은 "권한이 없는" 게 아니라 "세션이 없는" 것이다.
            // 클라이언트는 저장된 토큰을 지우고 로그인 화면으로 간다(GET /users/me 와 같은 응답).
            return isAllowedWhenWithdrawn(request) ? null : new Blocked(ErrorCode.LOGIN_REQUIRED, null);
        }

        if (isAlwaysAllowed(request)) return null;
        if (status != UserStatus.SUSPENDED) return null;   // ACTIVE 는 sanctions 를 읽지 않는다

        // SUSPENDED 인데 활성 제재가 없으면 스스로 되돌린다. 해제 배치가 밀려도 사용자가
        // 해제일 이후까지 묶이지 않게 하는 방어다(온보딩 부록 A).
        Optional<Sanction> active = sanctionService.activeSanction(userId);
        if (active.isEmpty()) {
            sanctionService.syncStatus(userId, Instant.now());
            return null;
        }
        return blockedBy(active.get(), request);
    }

    /** 막아야 하면 (코드, 해제 예정 시각)을, 통과시켜야 하면 null 을 준다. */
    private Blocked blockedBy(Sanction sanction, HttpServletRequest request) {
        SanctionType type = sanction.getType();
        String until = (sanction.getEndsAt() == null) ? null : sanction.getEndsAt().toString();

        if (type == SanctionType.BAN) return new Blocked(ErrorCode.ACCOUNT_BANNED, null);
        if (type == SanctionType.LOCK)
            return isReadOnly(request) ? null : new Blocked(ErrorCode.ACCOUNT_LOCKED, until);

        // FEATURE_SUSPENSION — 지정한 기능만 막는다. 나머지는 정상 동작해야 한다.
        FeatureCode feature = sanction.getFeatureCode();
        if (feature == null || !feature.blocks(request.getMethod(), request.getRequestURI())) return null;
        // 해당 API 명세가 고유 코드를 정해 둔 기능은 그 코드를 내린다 — 클라가 화면별로 분기한다.
        return new Blocked(feature.errorCode(), until);
    }

    /** 차단 사유와, 클라가 곧바로 안내할 수 있는 해제 예정 시각. */
    private record Blocked(ErrorCode code, String suspendedUntil) {}

    private boolean isReadOnly(HttpServletRequest request) {
        return READ_ONLY_METHODS.contains(request.getMethod());
    }

    private boolean isAlwaysAllowed(HttpServletRequest request) {
        return ALWAYS_ALLOWED.contains(request.getMethod() + " " + request.getRequestURI());
    }

    private boolean isAllowedWhenWithdrawn(HttpServletRequest request) {
        return ALLOWED_WHEN_WITHDRAWN.contains(request.getMethod() + " " + request.getRequestURI());
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

    private void writeError(HttpServletResponse response, Blocked blocked) throws IOException {
        response.setStatus(blocked.code().getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(
                ErrorResponse.of(blocked.code(), blocked.suspendedUntil()))));
    }
}
