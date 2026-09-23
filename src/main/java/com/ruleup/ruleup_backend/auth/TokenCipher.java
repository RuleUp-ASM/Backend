package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * IdP 토큰 저장용 애플리케이션 암호화 (AES-256-GCM).
 * 저장 형식: [12B nonce || ciphertext+tag] — social_tokens.access_token_enc/refresh_token_enc.
 * 키: env(SOCIAL_TOKEN_ENC_KEY, Base64 32바이트) 우선, 미설정 시 JWT secret의 SHA-256 파생(개발 폴백).
 */
@Component
public class TokenCipher {

    /** 키 회전 대비 버전 표시 (social_tokens.encryption_key_version). */
    public static final int KEY_VERSION = 1;

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(AppProperties props) {
        this.key = new SecretKeySpec(resolveKey(props), "AES");
    }

    private static byte[] resolveKey(AppProperties props) {
        String configured = System.getenv("SOCIAL_TOKEN_ENC_KEY");
        try {
            if (configured != null && !configured.isBlank()) {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length == 32) return raw;
            }
            // 개발 폴백 — JWT secret 파생(32바이트). 운영은 전용 키를 env 로 주입한다.
            return MessageDigest.getInstance("SHA-256")
                    .digest(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("social token encryption key init failed", e);
        }
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_LENGTH + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LENGTH);
            System.arraycopy(encrypted, 0, out, NONCE_LENGTH, encrypted.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("social token encrypt failed", e);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null) return null;
        try {
            byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(stored, NONCE_LENGTH, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("social token decrypt failed", e);
        }
    }
}
