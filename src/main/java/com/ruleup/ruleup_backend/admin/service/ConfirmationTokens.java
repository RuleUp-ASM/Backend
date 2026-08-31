package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 2단계 확인 토큰 — 백오피스 백엔드 4-2.
 *
 * <p><b>2단계 확인은 UX 가 아니라 안전장치다.</b> 서버가 토큰을 요구하지 않으면 클라이언트
 * 모달만으로는 오조작을 막지 못한다 — 스크립트나 재시도가 그대로 통과하기 때문이다.
 *
 * <p>토큰은 <b>(운영자, 조작, 대상, 요청 본문)에 묶인다</b>. 대상이나 내용이 바뀌면 검증에
 * 실패하므로 "A 를 확인하고 B 를 집행"하는 사고가 구조적으로 막힌다.
 *
 * <p>상태를 두지 않는다(HMAC + 만료 시각). 확인 대기를 DB 에 쌓으면 정리 배치가 하나 더 늘고,
 * 5분짜리 임시값에 그럴 이유가 없다.
 */
@Component
@RequiredArgsConstructor
public class ConfirmationTokens {

    private static final String ALGO = "HmacSHA256";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String SEP = ".";

    private final AppProperties props;

    /** 이 요청에 한해 유효한 확인 토큰. */
    public String issue(UUID operatorId, String action, String targetId, String payload) {
        long expiresAt = Instant.now().plus(TTL).toEpochMilli();
        String body = expiresAt + SEP + fingerprint(operatorId, action, targetId, payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((body + SEP + sign(body)).getBytes(StandardCharsets.UTF_8));
    }

    /** 위조·만료·다른 요청의 토큰이면 false. */
    public boolean verify(String token, UUID operatorId, String action, String targetId, String payload) {
        if (token == null || token.isBlank()) return false;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int lastSep = decoded.lastIndexOf(SEP);
            if (lastSep < 0) return false;

            String body = decoded.substring(0, lastSep);
            if (!constantTimeEquals(sign(body), decoded.substring(lastSep + 1))) return false;

            String[] parts = body.split("\\" + SEP, 2);
            if (Instant.ofEpochMilli(Long.parseLong(parts[0])).isBefore(Instant.now())) return false;
            return constantTimeEquals(parts[1], fingerprint(operatorId, action, targetId, payload));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 확인한 내용과 집행할 내용이 같은지 대조하는 지문. */
    private String fingerprint(UUID operatorId, String action, String targetId, String payload) {
        return hash(operatorId + "|" + action + "|" + targetId + "|" + (payload == null ? "" : payload));
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), ALGO));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("확인 토큰 서명 실패", e);
        }
    }

    private String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("확인 토큰 지문 생성 실패", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
