package com.ruleup.ruleup_backend.security;

import com.ruleup.ruleup_backend.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 발급/검증 (HS256, 테크 스펙 3.6).
 * Access(30분)·Refresh(30일)·Signup(5분)을 발급하고 서명/만료를 검증한다.
 * 비밀키·만료시간은 .env → AppProperties에서 주입.
 */
@Component
public class JwtProvider {

    private static final String CLAIM_TYPE = "type";

    private final SecretKey key;
    private final long accessTtl;
    private final long refreshTtl;
    private final long signupTtl;

    public JwtProvider(AppProperties props) {
        AppProperties.Jwt jwt = props.jwt();
        this.key = Keys.hmacShaKeyFor(jwt.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = jwt.accessTokenTtl();
        this.refreshTtl = jwt.refreshTokenTtl();
        this.signupTtl = jwt.signupTokenTtl();
    }

    /** 액세스 토큰 (subject = userId) */
    public String issueAccessToken(UUID userId) {
        return build(userId.toString(), TokenType.ACCESS, accessTtl);
    }

    /** 리프레시 토큰 (subject = userId). 원문은 호출측에서 hash로 DB 저장 */
    public String issueRefreshToken(UUID userId) {
        return build(userId.toString(), TokenType.REFRESH, refreshTtl);
    }

    /** 가입 완료 전 임시 토큰 (provider/email을 클레임에 담음) */
    public String issueSignupToken(String oauthSubject, String provider, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti: 토큰 유일성 보장
                .subject(oauthSubject)
                .claim(CLAIM_TYPE, TokenType.SIGNUP.name())
                .claim("provider", provider)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(signupTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 서명·만료 검증 후 클레임 반환. 실패 시 jjwt 예외를 던진다 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    private String build(String subject, TokenType type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti: 같은 초에 발급돼도 토큰(=해시)이 유일해지도록
                .subject(subject)
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}