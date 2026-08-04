package com.ruleup.ruleup_backend.user.domain;

import java.util.regex.Pattern;
import java.time.Duration;

/**
 * 닉네임 규칙: 2~12자, 한글·영문·숫자만 (특수문자·공백 불가). 안드 화면 규칙과 동일.
 * 자음만 나열(ㄱㄱㄱㄱ)은 허용하되 모음만 나열(ㅏㅏㅏ)은 불허 — 회원 정책 §3.
 * (모음 자모 ㅏ-ㅣ는 완성형 음절(가-힣)에만 포함될 수 있으므로 허용 문자에서 제외)
 */
public final class NicknamePolicy {

    private static final Pattern PATTERN = Pattern.compile("^[가-힣ㄱ-ㅎa-zA-Z0-9]{2,12}$");

    public static final Duration CHANGE_INTERVAL = Duration.ofDays(30);   // 닉네임 변경 제한 주기

    private NicknamePolicy() {}

    public static boolean isValid(String nickname) {
        return nickname != null && PATTERN.matcher(nickname).matches();
    }
}