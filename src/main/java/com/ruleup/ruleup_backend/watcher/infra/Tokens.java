package com.ruleup.ruleup_backend.watcher.infra;

import java.security.SecureRandom;
import java.util.Base64;

/** 추측 불가능한 URL-safe 랜덤 토큰(초대·수신거부 링크용). */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {}

    /** 접두사 + 256bit 랜덤(예: inv_xxxx). */
    public static String generate(String prefix) {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return prefix + URL.encodeToString(buf);
    }
}
