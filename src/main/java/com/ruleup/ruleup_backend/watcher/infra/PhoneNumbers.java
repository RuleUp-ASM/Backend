package com.ruleup.ruleup_backend.watcher.infra;

/**
 * 휴대폰 번호 정규화·검증·마스킹. MVP는 한국 휴대폰(010xxxxxxxx) 기준.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** 숫자만 남긴다(하이픈/공백 제거). */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /** 한국 휴대폰 형식(010 + 8자리 = 11자리). */
    public static boolean isValid(String raw) {
        String n = normalize(raw);
        return n.length() == 11 && n.startsWith("010");
    }

    /** 마스킹: 01012345678 → 010-****-5678 (생성자 노출용). */
    public static String mask(String raw) {
        String n = normalize(raw);
        if (n.length() != 11) return "***";
        return n.substring(0, 3) + "-****-" + n.substring(7);
    }
}
