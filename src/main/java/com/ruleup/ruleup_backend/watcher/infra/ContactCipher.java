package com.ruleup.ruleup_backend.watcher.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비유저 감시자 연락처 암호화(§5.9 — 연락처는 암호화 저장, 생성자에겐 마스킹만).
 * AES-256-GCM. 저장 포맷 = [12B IV][ciphertext+tag].
 *
 * 키는 설정 {@code app.watcher.contact-secret}(base64) 에서 읽는다(prod=Secrets Manager, 로컬=.env).
 * 미설정 시 프로세스 임시 키를 생성하고 경고한다 — 로컬/테스트 전용(재기동 시 복호화 불가). prod는 반드시 설정.
 */
@Component
public class ContactCipher {

    private static final Logger log = LoggerFactory.getLogger(ContactCipher.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ContactCipher(@Value("${app.watcher.contact-secret:}") String secretB64) {
        if (secretB64 == null || secretB64.isBlank()) {
            this.key = generateEphemeralKey();
            log.warn("app.watcher.contact-secret 미설정 — 프로세스 임시 키로 감시자 연락처를 암호화합니다. "
                    + "로컬/테스트 전용이며 재기동 시 기존 데이터를 복호화할 수 없습니다. prod에서는 반드시 설정하세요.");
        } else {
            byte[] raw = Base64.getDecoder().decode(secretB64.trim());
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("연락처 암호화 실패", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(stored, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("연락처 복호화 실패", e);
        }
    }

    private SecretKey generateEphemeralKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            return kg.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("임시 키 생성 실패", e);
        }
    }
}
