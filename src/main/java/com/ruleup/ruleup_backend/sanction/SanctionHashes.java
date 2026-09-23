package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 밴리스트 해시 — {@code HMAC-SHA256(salt, 값)}.
 *
 * <p>원본 식별자를 보관하지 않기 위한 것이므로 <b>솔트가 바뀌면 기존 차단이 전부 무력화</b>된다.
 * 운영에서 로테이션하려면 재해시 배치가 선행돼야 한다.
 */
@Component
@RequiredArgsConstructor
public class SanctionHashes {

    private static final String ALGO = "HmacSHA256";

    private final AppProperties props;

    public String ofOauth(String provider, String subject) {
        return hmac(provider + ":" + subject);
    }

    public String ofInstallation(String installationId) {
        return (installationId == null || installationId.isBlank()) ? null : hmac(installationId);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(props.security().banSalt().getBytes(StandardCharsets.UTF_8), ALGO));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("밴리스트 해시 생성 실패", e);
        }
    }
}
