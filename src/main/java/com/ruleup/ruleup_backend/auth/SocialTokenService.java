package com.ruleup.ruleup_backend.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ruleup.ruleup_backend.auth.domain.SocialToken;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.oauth.OAuthUserInfo;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * IdP 토큰 암호화 저장 (social_tokens — unlink 근거).
 * - 기존 회원: 로그인마다 최신 토큰으로 upsert.
 * - 신규 회원: OAuth 교환 시점엔 user 행이 없으므로 signupToken jti 로 잠시 보류(pending)했다가
 *   가입 트랜잭션에서 flush 한다 (TTL = signupToken 수명, 단일 인스턴스 전제 — 스케일 시 Redis).
 */
@Service
@RequiredArgsConstructor
public class SocialTokenService {

    private final SocialTokenRepository socialTokenRepository;
    private final TokenCipher tokenCipher;

    /** signupToken jti → IdP 토큰 보류 저장소. */
    private final Cache<String, OAuthUserInfo.IdpTokens> pending = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(6))
            .maximumSize(100_000)
            .build();

    /** 신규 가입 대기 중 IdP 토큰 보류 (로그인 신규 분기에서 호출). */
    public void hold(String signupJti, OAuthUserInfo.IdpTokens tokens) {
        if (tokens != null && tokens.accessToken() != null) pending.put(signupJti, tokens);
    }

    /** 가입 완료 시 보류분 저장 (가입 트랜잭션 내부에서 호출). */
    public void flushPending(String signupJti, UUID userId, OAuthProvider provider) {
        OAuthUserInfo.IdpTokens tokens = pending.getIfPresent(signupJti);
        if (tokens == null) return;
        pending.invalidate(signupJti);
        upsert(userId, provider, tokens);
    }

    /** 로그인마다 최신 IdP 토큰으로 갱신 저장. 트랜잭션이 있으면 참여, 없으면 새로 연다. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void upsert(UUID userId, OAuthProvider provider, OAuthUserInfo.IdpTokens tokens) {
        if (tokens == null || tokens.accessToken() == null) return;
        byte[] accessEnc = tokenCipher.encrypt(tokens.accessToken());
        byte[] refreshEnc = tokenCipher.encrypt(tokens.refreshToken());
        socialTokenRepository.findById(new SocialToken.Key(userId, provider))
                .ifPresentOrElse(
                        t -> t.rotate(accessEnc, refreshEnc, TokenCipher.KEY_VERSION, tokens.expiresAt()),
                        () -> socialTokenRepository.save(SocialToken.of(userId, provider,
                                accessEnc, refreshEnc, TokenCipher.KEY_VERSION, tokens.expiresAt())));
    }
}
