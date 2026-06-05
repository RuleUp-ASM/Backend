package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.common.error.ErrorResponse;
import com.ruleup.ruleup_backend.common.response.ApiResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;   // ← Jackson 3

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 요청마다 Authorization: Bearer {AT} 검사. 유효 AT면 SecurityContext에 userId 등록.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final JsonMapper JSON = JsonMapper.builder().build();  // 직접 생성(주입 불필요)

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);   // 토큰 없음 → 통과(보호 경로면 EntryPoint가 401)
            return;
        }

        String token = header.substring(PREFIX.length());
        try {
            Claims claims = jwtProvider.parse(token);
            if (!TokenType.ACCESS.name().equals(claims.get("type"))) {  // 액세스 토큰만 허용
                writeError(response, ErrorCode.LOGIN_REQUIRED);
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(), null, AuthorityUtils.NO_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            writeError(response, ErrorCode.LOGIN_REQUIRED);
        } catch (JwtException | IllegalArgumentException e) {
            writeError(response, ErrorCode.LOGIN_REQUIRED);
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(ErrorResponse.of(code))));
    }
}