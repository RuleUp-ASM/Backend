package com.ruleup.ruleup_backend.challenge.recommendation;

/**
 * 제목/설명을 보고 챌린지 기본 설정(참여방식·일정·패널티·보상·익명)을 제안하는 LLM 클라이언트.
 * 실패(타임아웃/파싱오류 등)는 ChallengeSettings.empty() 로 → 서버가 정적 기본값으로 폴백한다.
 */
public interface ChallengeSettingsClient {
    ChallengeSettings suggest(String title, String description);
}