package com.ruleup.ruleup_backend.challenge;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 인증 방식(다중 선택). challenges.verification_methods(JSON)에 코드 문자열 배열로 저장.
 * 값 유효성은 앱에서 검증(allValid).
 */
public enum VerificationMethod {
    GPS, PHOTO, SCREEN_TIME, SELF_CHECK;

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 전달된 코드가 전부 정의된 4종 안에 있는지 (오타·미정의 코드 검사) */
    public static boolean allValid(Collection<String> codes) {
        return codes != null && codes.stream().allMatch(CODES::contains);
    }
}