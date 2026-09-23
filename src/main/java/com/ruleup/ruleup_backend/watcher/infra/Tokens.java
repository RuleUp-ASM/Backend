package com.ruleup.ruleup_backend.watcher.infra;

import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.time.Instant;

/** Authenticated, encrypted claims: no user/challenge identifiers are visible in the URL. */
@Component
public class Tokens {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private static final byte[] PURPOSE = "ruleup:watcher-invitation:v1".getBytes(StandardCharsets.UTF_8);
    public Tokens(AppProperties props) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            key = new SecretKeySpec(mac.doFinal(PURPOSE), "AES");
        } catch (Exception e) { throw new IllegalStateException("watcher token key", e); }
    }
    public String issue(UUID challengeId, UUID inviterId, Instant expiresAt) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            ByteBuffer claims = ByteBuffer.allocate(40);
            claims.putLong(challengeId.getMostSignificantBits()).putLong(challengeId.getLeastSignificantBits());
            claims.putLong(inviterId.getMostSignificantBits()).putLong(inviterId.getLeastSignificantBits());
            claims.putLong(expiresAt.toEpochMilli());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PURPOSE);
            byte[] encrypted = cipher.doFinal(claims.array());
            return "wtk1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception e) { throw new IllegalStateException("watcher token issue", e); }
    }
    public Claims verify(String token) {
        try {
            if (token == null || !token.startsWith("wtk1_") || token.length() > 150) throw new IllegalArgumentException();
            byte[] raw = Base64.getUrlDecoder().decode(token.substring(5));
            if (raw.length != 68) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
            cipher.updateAAD(PURPOSE);
            ByteBuffer claims = ByteBuffer.wrap(cipher.doFinal(Arrays.copyOfRange(raw, 12, raw.length)));
            return new Claims(new UUID(claims.getLong(), claims.getLong()),
                    new UUID(claims.getLong(), claims.getLong()), Instant.ofEpochMilli(claims.getLong()));
        } catch (Exception e) { throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND); }
    }
    public record Claims(UUID challengeId, UUID inviterId, Instant expiresAt) {}
}
