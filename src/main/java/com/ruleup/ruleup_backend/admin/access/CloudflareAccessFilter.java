package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.admin.access.CloudflareAccessVerifier.AccessIdentity;
import com.ruleup.ruleup_backend.admin.access.CloudflareAccessVerifier.VerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 관리자 서비스의 유일한 인증 — Cloudflare Access 가 붙여 준 JWT.
 *
 * <p>순서대로 거른다. 하나라도 어긋나면 컨트롤러에 닿지 않는다.
 * <ol>
 *   <li><b>Access JWT</b>({@code Cf-Access-Jwt-Assertion}) 서명·발급자·대상·만료 — {@link CloudflareAccessVerifier}.
 *       {@code Cf-Access-Authenticated-User-Email} 같은 평문 헤더는 읽지 않는다</li>
 *   <li><b>운영자 허용 목록</b> — Access 정책과 별개로 서버가 한 번 더 본다</li>
 *   <li><b>Origin</b> — 상태를 바꾸는 요청은 콘솔 오리진에서 온 것만. Access 쿠키는 브라우저가
 *       자동으로 싣기 때문에, 다른 사이트가 콘솔로 POST 를 보내게 만드는 CSRF 를 여기서 끊는다</li>
 *   <li><b>운영자 계정</b> — 이메일에 연결된 운영자 계정을 주체로 세운다. 뒤의
 *       {@code AdminAccessInterceptor} 가 롤·탈퇴 여부를 DB 로 다시 확인한다</li>
 * </ol>
 * 앱 사용자 JWT({@code Authorization: Bearer})는 이 서비스에서 <b>아무 의미가 없다</b> — 읽지 않는다.
 *
 * <p>거부 응답에는 사유를 싣지 않는다. 사유는 로그에만, 토큰 원문 없이 남긴다.
 */
@Slf4j
public class CloudflareAccessFilter extends OncePerRequestFilter {

    public static final String ASSERTION_HEADER = "Cf-Access-Jwt-Assertion";
    /** 감사 요청 로그가 읽는 요청 속성 — 누가 보냈는지. */
    public static final String ACTOR_EMAIL_ATTRIBUTE = CloudflareAccessFilter.class.getName() + ".email";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final CloudflareAccessVerifier verifier;
    private final AdminAccessProperties properties;
    private final OperatorDirectory operators;

    public CloudflareAccessFilter(CloudflareAccessVerifier verifier, AdminAccessProperties properties,
                                  OperatorDirectory operators) {
        this.verifier = verifier;
        this.properties = properties;
        this.operators = operators;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 컨테이너 헬스체크는 태스크 안(localhost)에서만 온다. 관리자 서비스는 인입 포트가 없다.
        return "/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isConfigured()) {
            log.error("admin_access_denied reason=NOT_CONFIGURED — app.admin.access(team-domain·audiences·allowed-emails)를 확인한다");
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_ACCESS_NOT_CONFIGURED");
            return;
        }

        AccessIdentity identity;
        try {
            identity = verifier.verify(request.getHeader(ASSERTION_HEADER));
        } catch (VerificationException e) {
            log.warn("admin_access_denied reason={} method={} path={}", e.failure(), request.getMethod(), request.getRequestURI());
            reject(response, HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED");
            return;
        }

        if (!properties.emailSet().contains(identity.email())) {
            log.warn("admin_access_denied reason=NOT_ALLOWLISTED actor={} path={}", identity.email(), request.getRequestURI());
            reject(response, HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN");
            return;
        }

        if (!SAFE_METHODS.contains(request.getMethod()) && !trustedOrigin(request)) {
            log.warn("admin_access_denied reason=BAD_ORIGIN actor={} origin={} path={}",
                    identity.email(), request.getHeader("Origin"), request.getRequestURI());
            reject(response, HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN");
            return;
        }

        Optional<UUID> operatorId = operators.resolve(identity.email());
        if (operatorId.isEmpty()) {
            log.warn("admin_access_denied reason=OPERATOR_WITHDRAWN actor={}", identity.email());
            reject(response, HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN");
            return;
        }

        request.setAttribute(ACTOR_EMAIL_ATTRIBUTE, identity.email());
        var auth = new UsernamePasswordAuthenticationToken(operatorId.get().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 브라우저는 상태 변경 요청에 Origin 을 싣는다. 없거나 목록 밖이면 거부한다.
     * {@code Sec-Fetch-Site} 가 오면 그것도 본다 — 같은 사이트가 아니면 거부.
     */
    private boolean trustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || !properties.allowedOrigins().contains(origin.replaceAll("/+$", ""))) return false;
        String site = request.getHeader("Sec-Fetch-Site");
        return site == null || site.equals("same-origin");
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"접근 권한이 없어요.\"}}");
    }
}
