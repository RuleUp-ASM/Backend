package com.ruleup.ruleup_backend.user;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** 관심 카테고리 15종 (안드 enum·V1 DB CHECK와 동일해야 함). */
public enum InterestCategory {
    EXERCISE, READING, MEDITATION, HEALTH, WAKE_UP,
    WORK, STUDY, HOBBY, COOKING, FINANCE,
    ENVIRONMENT, RELATIONSHIP, MUSIC, WRITING, CODING;

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    public static boolean allValid(Collection<String> codes) {
        return codes.stream().allMatch(CODES::contains);
    }
}