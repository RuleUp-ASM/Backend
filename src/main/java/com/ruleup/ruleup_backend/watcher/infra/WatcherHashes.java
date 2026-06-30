package com.ruleup.ruleup_backend.watcher.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 결정적 해시 유틸. 차단 대조키(subjectKey)·번호 해시·OTP 코드 해시에 사용(원본 PII 저장 회피).
 * SHA-256 hex(64자). 비교 전용이라 솔트 불필요(같은 입력=같은 키여야 차단/대조가 동작).
 */
public final class WatcherHashes {

    private WatcherHashes() {}

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("해시 계산 실패", e);
        }
    }

    /** 유저 감시자 차단키. */
    public static String subjectKeyForUser(UUID userId) { return sha256Hex("U:" + userId); }

    /** 비유저 감시자 차단키(정규화된 번호 기준). */
    public static String subjectKeyForPhone(String normalizedPhone) { return sha256Hex("P:" + normalizedPhone); }
}
