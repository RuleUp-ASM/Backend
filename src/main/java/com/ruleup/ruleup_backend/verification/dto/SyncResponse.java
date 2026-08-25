package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * POST /api/v1/verifications/sync 응답.
 *
 * @param syncedAt           서버 처리 시각(ISO-8601, KST)
 * @param flushIntervalSec   다음 sync 주기(기본 1800). 매 ACK마다 전체값으로 회신하며 기기 스펙 기반으로 서버가 산정
 * @param updatedChallenges  이번 sync로 상태가 바뀐 챌린지만
 * @param ignoredSignalTypes 무시한 신호 타입
 * @param maxPayloadBytes    한 번에 보낼 수 있는 상한 — 클라는 이 값을 보고 전송 구간을 쪼갠다
 */
public record SyncResponse(
        String syncedAt,
        int flushIntervalSec,
        List<UpdatedChallenge> updatedChallenges,
        List<String> ignoredSignalTypes,
        int maxPayloadBytes
) {
    /**
     * @param challengeId  챌린지 ID
     * @param todayStatus  IN_PROGRESS / CHECKING / DONE / FAILED — 오늘 인증 결과 조회의 status와 동일 enum
     * @param progressRate 사이클 진행률(%). 확정된 인증 결과 기준
     */
    public record UpdatedChallenge(String challengeId, String todayStatus, java.math.BigDecimal progressRate) {}
}
