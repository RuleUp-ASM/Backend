package com.ruleup.ruleup_backend.challenge.dto;

import java.util.List;

/**
 * 홈 카테고리 그리드(탐색 §2.2). 카테고리 정적 목록 + 진행 중(now < endAt) 챌린지 수.
 * Caffeine 캐시(TTL 10분), 표시용 수치라 10분 이내 지연 허용.
 */
public record CategoryGridResponse(List<Item> items) {
    public record Item(Long categoryId, String name, Integer activeChallengeCount) {}
}
