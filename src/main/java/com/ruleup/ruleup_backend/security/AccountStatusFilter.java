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
 * {@code sanctions} 를 조회해 차단 범위를 정한다. 정상 사용자는 두 번째 조회를 하지 않으므로
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
        if (isAlwaysAllowed(request)) return null;

        UserStatus status = userRepository.findStatusById(userId).orElse(null);
        if (status != UserStatus.SUSPENDED) return null;  // ACTIVE·WITHDRAWN 은 sanctions 를 읽지 않는다

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
