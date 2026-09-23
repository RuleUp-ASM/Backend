package com.ruleup.ruleup_backend.score.domain;

/**
 * 티어 체계 (user_score_summaries.actual_tier/display_tier — 매너 온도 대체, 오픈 이슈 #1).
 * 가입 시 BRONZE 10점으로 시작한다. 승급/강등·유예 밴드 계산은 온도 계산(티어) 스펙 소관 —
 * 이 모듈은 가입 초기값 부여와 응답 필드 조립만 담당한다.
 */
public enum Tier {
    UNRANKED, BRONZE, SILVER, GOLD, DIAMOND, RUBY
}
