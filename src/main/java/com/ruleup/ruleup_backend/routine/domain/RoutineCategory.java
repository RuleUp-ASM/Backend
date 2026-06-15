package com.ruleup.ruleup_backend.routine.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 루틴 카테고리 15종. routine_template.category DB ENUM 과 1:1.
 *
 * ※ 주의: 관심 카테고리(InterestCategory)는 'WAKE_UP'(언더스코어)인데,
 *   루틴 템플릿 카테고리는 'WAKEUP'(원본 스키마 그대로)이다. 둘은 별개 코드 체계다.
 */
public enum RoutineCategory {
    EXERCISE, READING, MEDITATION, HEALTH, WAKEUP,
    WORK, STUDY, HOBBY, COOKING, FINANCE,
    ENVIRONMENT, RELATIONSHIP, MUSIC, WRITING, CODING;

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    public static boolean allValid(Collection<String> codes) {
        return codes != null && codes.stream().allMatch(CODES::contains);
    }
}