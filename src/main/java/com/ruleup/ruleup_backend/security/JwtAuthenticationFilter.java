package com.ruleup.ruleup_backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청마다 Authorization: Bearer {AT} 검사. 유효한 ACCESS 토큰이면 SecurityContext에 userId 등록.
 *
 *   토큰이 없거나·만료·위조여도 이 필터에서 직접 401을 내지 않는다.
 *    인증만 세팅하지 않고 통과시키면:
 *      - 공개 경로(로그인·가입·토큰재발급·닉네임검사 등)는 정상 처리되고,
 *      - 보호 경로는 뒤의 AuthorizationFilter → JwtAuthenticationEntryPoint 가 401(LOGIN_REQUIRED)을 낸다.
 *    (예전엔 만료·위조 시 필터가 직접 401을 내서, 이미 로그인돼 있던 유저가 만료된 토큰을 달고
 *     로그인 버튼을 누르면 공개 엔드포인트인데도 401이 나는 문제가 있었다.)
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                Claims claims = jwtProvider.parse(token);
                // ACCESS 토큰일 때만 인증 등록. 그 외 타입이면 무시(인증 미설정)하고 통과.
                if (TokenType.ACCESS.name().equals(claims.get("type"))) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, AuthorityUtils.NO_AUTHORITIES);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // 만료(ExpiredJwtException 포함)·위조 토큰: 막지 않고 통과(인증 미설정).
                // 보호 경로면 EntryPoint가 401을 낸다. 공개 경로는 정상 진행.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}