package com.ruleup.ruleup_backend.auth.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 심사 코드의 정규화·해시 규칙 (심사 계정 테크 스펙 5-2 · 5-3).
 *
 * <p>설정에는 원문 대신 <b>정규화한 코드의 SHA-256 소문자 hex</b> 만 둔다. 운영자가 로컬에서
 * {@code printf '<정규화한 코드>' | shasum -a 256} 으로 만든 값과 서버가 입력을 비교하는 값이 같아야 하므로
 * 규칙은 여기 한 곳에만 둔다.
 */
public final class ReviewCodes {

    private ReviewCodes() {}

    /** 대문자로 바꾸고 하이픈·공백을 모두 지운다. null 이면 빈 문자열. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(c -> c != '-' && !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                .forEach(sb::appendCodePoint);
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /** SHA-256 소문자 hex 64자. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
