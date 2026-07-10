package com.ruleup.ruleup_backend.challenge.domain;

import java.util.Optional;

/**
 * 챌린지 탐색 목록 정렬 7종(탐색 스펙 §3.2). 방향은 정의로 고정(사용자 미노출).
 *  - TRENDING          : challenge.trendingScore desc (기본)
 *  - TEMPLATE_USAGE    : template.usageCount desc (템플릿 파생 챌린지들의 참여자 합)
 *  - PARTICIPANTS      : challenge.participantCount desc
 *  - COMPLETION_RATE   : template.completionRate desc (표본 부족 시 null → 최하위)
 *  - SUCCESS_FAIL_RATIO: 방 성공률 desc (표본/진행 부족 시 null → 최하위)
 *  - RECENT            : challenge.createdAt desc
 *  - DEADLINE          : challenge.endDate asc (마감 임박 우선)
 * 동점은 challengeId desc 보조 키. 값 없음(null) 행은 항상 최하위(§3.2.8).
 */
public enum ExploreSort {
    TRENDING(true),
    TEMPLATE_USAGE(true),
    PARTICIPANTS(true),
    COMPLETION_RATE(true),
    SUCCESS_FAIL_RATIO(true),
    RECENT(true),
    DEADLINE(false);   // 오름차순(종료 임박 우선)

    /** true=내림차순(값 큰 것 위), false=오름차순. */
    private final boolean descending;

    ExploreSort(boolean descending) {
        this.descending = descending;
    }

    public boolean isDescending() { return descending; }

    /** 문자열 → enum(대소문자 무시). 정의되지 않은 키면 empty. */
    public static Optional<ExploreSort> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.of(TRENDING);
        try {
            return Optional.of(ExploreSort.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
