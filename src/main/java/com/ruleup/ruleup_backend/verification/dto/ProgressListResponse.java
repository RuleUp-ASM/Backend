package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/** §3.2 진행률 일괄 조회 응답 봉투. data 가 객체여야 하므로 asOf + challenges[] 로 감싼다. */
public record ProgressListResponse(String asOf, List<ChallengeProgress> challenges) {}
