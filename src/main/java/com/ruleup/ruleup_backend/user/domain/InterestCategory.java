package com.ruleup.ruleup_backend.user.domain;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관심 카테고리 15종 (안드 enum · V1 DB CHECK · GET /categories 응답과 모두 동일해야 함).
 * 각 상수는 화면 표시용 label(한글명)을 함께 들고 있다.
 * (아이콘/이모지는 클라이언트가 code로 매핑 → 서버는 code·label만 진실로 제공)
 */
@Getter
public enum InterestCategory {
    EXERCISE("운동"),
    READING("독서"),
    MEDITATION("명상"),
    HEALTH("건강"),
    WAKE_UP("기상"),
    WORK("업무"),
    STUDY("학습"),
    HOBBY("취미"),
    COOKING("요리"),
    FINANCE("재테크"),
    ENVIRONMENT("환경"),
    RELATIONSHIP("관계"),
    MUSIC("음악"),
    WRITING("글쓰기"),
    CODING("코딩");

    /** 사용자가 한 번에 선택할 수 있는 최대 개수 (스펙 4.7 maxSelectable) */
    public static final int MAX_SELECTABLE = 6;

    private final String label;

    InterestCategory(String label) {
        this.label = label;
    }

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 모든 코드가 정의된 15종 안에 들어있는지 (오타·없는 코드 검사) */
    public static boolean allValid(Collection<String> codes) {
        return codes.stream().allMatch(CODES::contains);
    }

    /** 선택 개수가 1~6개 범위인지 (스펙: 1~6개) */
    public static boolean isCountValid(Collection<String> codes) {
        int n = codes.size();
        return n >= 1 && n <= MAX_SELECTABLE;
    }
}