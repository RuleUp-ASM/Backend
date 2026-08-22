package com.ruleup.ruleup_backend.verification.dto;

/**
 * 연속 기록(스트릭) 변화. 그 판정 직전 값과 직후 값을 함께 내려 클라가 "7일째!" 같은 연출을 만든다.
 *
 * @param before 판정 전 연속 성공 일수
 * @param after  판정 후 연속 성공 일수(실패면 0)
 */
public record StreakChange(int before, int after) {}
