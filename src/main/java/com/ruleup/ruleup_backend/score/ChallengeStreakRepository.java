package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.ChallengeStreak;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChallengeStreakRepository extends JpaRepository<ChallengeStreak, ChallengeStreak.Key> {

    /** 연속 실패 n사이클 이상 도달자 — 방 내부 모듈의 경고·강퇴 대상 추출. */
    List<ChallengeStreak> findByChallengeIdAndFailureStreakGreaterThanEqual(UUID challengeId, int threshold);
}
