package com.ruleup.ruleup_backend.auth.domain;

import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 외부 소셜 제공자(IdP) 토큰 (social_tokens 테이블). PK = (user_id, provider).
 * 원문을 저장하지 않고 애플리케이션 암호화(AES-GCM: nonce+ciphertext+tag) 결과만 저장한다.
 * 용도: 탈퇴 1년 파기 시 IdP unlink 근거 (테크 스펙 5-1 인증 2계층).
 */
@Entity
@Table(name = "social_tokens")
@IdClass(SocialToken.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialToken {

    /** 복합 PK (user_id, provider). */
    public record Key(UUID userId, OAuthProvider provider) implements Serializable {}

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private OAuthProvider provider;

    @Column(name = "access_token_enc", nullable = false)
    private byte[] accessTokenEnc;

    @Column(name = "refresh_token_enc")
    private byte[] refreshTokenEnc;

    @Column(name = "encryption_key_version", nullable = false)
    private int encryptionKeyVersion;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public static SocialToken of(UUID userId, OAuthProvider provider,
                                 byte[] accessTokenEnc, byte[] refreshTokenEnc,
                                 int keyVersion, Instant expiresAt) {
        SocialToken t = new SocialToken();
        t.userId = userId;
        t.provider = provider;
        t.accessTokenEnc = accessTokenEnc;
        t.refreshTokenEnc = refreshTokenEnc;
        t.encryptionKeyVersion = keyVersion;
        t.expiresAt = expiresAt;
        return t;
    }

    /** 로그인마다 최신 IdP 토큰으로 갱신. */
    public void rotate(byte[] accessTokenEnc, byte[] refreshTokenEnc, int keyVersion, Instant expiresAt) {
        this.accessTokenEnc = accessTokenEnc;
        this.refreshTokenEnc = refreshTokenEnc;
        this.encryptionKeyVersion = keyVersion;
        this.expiresAt = expiresAt;
    }
}
