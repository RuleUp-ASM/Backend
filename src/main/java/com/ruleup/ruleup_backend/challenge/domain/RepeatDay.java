package com.ruleup.ruleup_backend.challenge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 반복 요일. challenges.repeat_days(JSON)에 코드 문자열 배열로 저장.
 * 예: ["MON","TUE","WED","THU","FRI"]
 */
public enum RepeatDay {
    MON, TUE, WED, THU, FRI, SAT, SUN;

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    public static boolean allValid(Collection<String> codes) {
        return codes != null && codes.stream().allMatch(CODES::contains);
    }
}