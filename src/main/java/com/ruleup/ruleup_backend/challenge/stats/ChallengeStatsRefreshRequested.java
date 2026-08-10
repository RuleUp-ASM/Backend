package com.ruleup.ruleup_backend.challenge.stats;

import java.util.UUID;

/**
 * 한 챌린지의 완주율·유지율을 다시 계산해 달라는 요청.
 *
 * <p>원본 트랜잭션(가입·탈퇴·강퇴·판정 확정·이의 처리) 안에서 발행하고, 실제 처리는
 * {@code AFTER_COMMIT} 이후다 — 통계 갱신이 실패해도 이미 성공한 사용자 행동을 되돌리지 않는다.
 *
 * @param reason 어떤 행동이 갱신을 유발했는지(로그·메트릭용)
 */
public record ChallengeStatsRefreshRequested(UUID challengeId, String reason) {

    public static ChallengeStatsRefreshRequested of(UUID challengeId, String reason) {
        return new ChallengeStatsRefreshRequested(challengeId, reason);
    }
}
