package com.ruleup.ruleup_backend.challenge.dto;

/**
 * 삭제 응답 (§8). penaltyApplied = 진행 중 삭제에서 챌린지 내 success 이력이 있어 탈퇴 패널티가 트리거됐는지.
 * 시작 전 삭제 또는 success 이력 없음 = false. 실제 온도 가감은 매너 온도 스펙 소관.
 * (공통 봉투 data 안에 담긴다: { "success": true, "data": { "penaltyApplied": false } })
 */
public record DeleteChallengeResponse(boolean penaltyApplied) {}
