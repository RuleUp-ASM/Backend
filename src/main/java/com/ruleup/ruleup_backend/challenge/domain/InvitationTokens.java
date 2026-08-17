package com.ruleup.ruleup_backend.challenge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 초대 토큰의 생성·해시 규칙 한 곳.
 *
 * <p>평문은 링크에 실려 나가고 서버에는 <b>해시만</b> 남는다 — DB 를 읽을 수 있게 된 사람이
 * 곧바로 남의 방에 들어갈 수 있으면 안 되기 때문이다. 발급(RoomAdminService)과 조회·수락
 * (ChallengeInvitationService)이 같은 규칙을 써야 하므로 양쪽이 각자 구현하지 않도록 여기 모은다.
 */
public final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InvitationTokens() {}

    /** URL 에 그대로 실을 수 있는 랜덤 토큰(256비트). */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 저장·조회용 SHA-256 해시(binary(32)). */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
