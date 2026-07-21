package com.ruleup.ruleup_backend.reputation.domain;

/**
 * 마일스톤 유형(마이프로필 평판 히스토리).
 *  - TIER_REACHED     : 온도 밴드 앵커(50/60/70/75/80/85/90) 첫 도달.
 *  - STREAK           : 연속 성공일 임계(10/30/50/100) 도달.
 *  - FIRST_COMPLETION : 첫 챌린지 완주.
 *  - SIGNUP           : 가입.
 */
public enum MilestoneType { TIER_REACHED, STREAK, FIRST_COMPLETION, SIGNUP }
