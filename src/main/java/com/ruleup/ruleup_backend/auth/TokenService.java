package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.auth.RefreshToken;
import com.ruleup.ruleup_backend.auth.RefreshTokenRepository;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.security.JwtProvider;
import com.ruleup.ruleup_backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 토큰 발급/저장 공용 로직.
 * Access/Refresh 발급 + Refresh는 hash로 DB 저장(원문은 클라이언트만 보관).
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AppProperties props;

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}

    @Transactional
    public TokenPair issueTokenPair(User user) {
        String access = jwtProvider.issueAccessToken(user.getId());
        String refresh = jwtProvider.issueRefreshToken(user.getId());
        Instant expiresAt = Instant.now().plusSeconds(props.jwt().refreshTokenTtl());
        refreshTokenRepository.save(RefreshToken.issue(user, sha256(refresh), expiresAt));
        return new TokenPair(access, refresh, props.jwt().accessTokenTtl());
    }

    /** Refresh 토큰 원문 → SHA-256 hex(64자). DB엔 이 hash만 저장. */
    public static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}