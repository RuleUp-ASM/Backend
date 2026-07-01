package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * §3.2 진행률 일괄 조회 응답 봉투.
 *
 * <p>안드로이드({@code ProgressResponse})는 최상위가 {@code {asOf, challenges[]}} 형태를 기대한다.
 * 기존에는 서버가 {@code List<ChallengeProgress>} 를 그대로 내려(bare array) 스펙과 어긋났다.
 * 이 래퍼로 감싸 안드 계약과 일치시킨다.
 *
 *  - asOf       : 스냅샷 기준 시각(ISO-8601, KST). 클라 캐시 무효화/표시용.
 *  - challenges : 챌린지별 진행률 항목.
 */
public record ProgressListResponse(
        String asOf,
        List<ChallengeProgress> challenges
) {}
