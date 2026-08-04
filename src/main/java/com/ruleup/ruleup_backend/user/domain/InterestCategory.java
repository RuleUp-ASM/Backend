package com.ruleup.ruleup_backend.user.domain;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관심 카테고리 12종 — 2026-08-03 확정본(테크 스펙 오픈 이슈 #3 해소).
 * 안드 enum · user_interests 저장값 · GET /categories 응답과 모두 동일해야 한다.
 * 각 상수는 화면 표시용 label(한글명)을 함께 들고 있다.
 * (아이콘/이모지는 클라이언트가 code로 매핑 → 서버는 code·label만 진실로 제공)
 */
@Getter
public enum InterestCategory {
    EXERCISE("운동"),
    WAKE_SLEEP("기상·수면"),
    DIET_HEALTH("식습관·건강"),
    STUDY("학습"),
    READING("독서"),
    MIND("마음"),
    FINANCE("재테크"),
    HOBBY("취미"),
    HOUSEKEEPING("정리·살림"),
    CAREER_PRODUCTIVITY("커리어·생산성"),
    DETOX("절제·디톡스"),
    ETC("기타");

    /** 사용자가 한 번에 선택할 수 있는 최대 개수 (계약: 0~6개, 건너뛰기 허용) */
    public static final int MAX_SELECTABLE = 6;

    private final String label;

    InterestCategory(String label) {
        this.label = label;
    }

    private static final Set<String> CODES =
            Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 모든 코드가 정의된 12종 안에 들어있는지 (오타·없는 코드 검사) */
    public static boolean allValid(Collection<String> codes) {
        return codes.stream().allMatch(CODES::contains);
    }

    /** 선택 개수가 0~6개 범위인지 (건너뛰기 = 빈 목록 허용) */
    public static boolean isCountValid(Collection<String> codes) {
        return codes.size() <= MAX_SELECTABLE;
    }
}
