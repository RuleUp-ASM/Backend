package com.ruleup.ruleup_backend.user;

import java.util.regex.Pattern;
import java.time.Duration;

/** 닉네임 규칙: 2~12자, 한글·영문·숫자만 (특수문자·공백 불가). 안드 화면 규칙과 동일. */
public final class NicknamePolicy {

    private static final Pattern PATTERN = Pattern.compile("^[가-힣a-zA-Z0-9]{2,12}$");

    public static final Duration CHANGE_INTERVAL = Duration.ofDays(30);   // 닉네임 변경 제한 주기

    private NicknamePolicy() {}

    public static boolean isValid(String nickname) {
        return nickname != null && PATTERN.matcher(nickname).matches();
    }
}