package com.ruleup.ruleup_backend.challenge.dto;

/**
 * 탈퇴 응답 (§6). penaltyApplied = 본인 success 이력이 있어 탈퇴 패널티가 트리거됐는지 여부.
 * 실제 온도 가감은 매너 온도 스펙 소관 — 본 API는 트리거 여부만 반환한다.
 */
public record LeaveResponse(boolean penaltyApplied) {}
