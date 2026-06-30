package com.ruleup.ruleup_backend.challenge.dto;

import java.math.BigDecimal;

/**
 * 3.5 삭제 응답 (§5.8).
 *  - mannerPenalty : 이번 삭제로 발생하는 평판 차감량(0이면 무차감). 실제 적용은 평판/패널티 스펙 소관.
 * (공통 봉투 data 안에 담긴다: { "success": true, "data": { "mannerPenalty": 0.5 } })
 */
public record DeleteChallengeResponse(BigDecimal mannerPenalty) {}
