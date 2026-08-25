package com.ruleup.ruleup_backend.verification.dto;

import java.util.List;

/**
 * POST /api/v1/verifications/sync 응답.
 *
 * @param syncedAt           서버 처리 시각(ISO-8601, KST)
 * @param flushIntervalSec   다음 sync 주기(기본 1800). 매 ACK마다 전체값으로 회신하며 기기 스펙 기반으로 서버가 산정
 * @param updatedChallenges  이번 sync로 상태가 바뀐 챌린지만
 * @param ignoredSignalTypes 무시한 신호 타입
 * @param maxPayloadBytes    한 번에 보낼 수 있는 본문 바이트 상한 — 클라는 이 값을 보고 전송 구간을 쪼갠다.
 *                           초과하면 413 SYNC_PAYLOAD_TOO_LARGE 로 반려된다
 * @param dedupDroppedCount  이미 받은 적이 있어 걸러낸 신호 수. 중복 수신은 정상 경로이며(오프라인 복구·
 *                           구간 재전송·FCM 기동), 이 값은 클라 재전송 동작을 관찰하기 위한 참고값이다
 */
public record SyncResponse(
        String syncedAt,
        int flushIntervalSec,
        List<UpdatedChallenge> updatedChallenges,
        List<String> ignoredSignalTypes,
        int maxPayloadBytes,
        int dedupDroppedCount
) {
    /**
     * @param challengeId  챌린지 ID
     * @param todayStatus  IN_PROGRESS / FAIL_EXPECTED / CHECKING / DONE / FAILED / NOT_TARGET
     *                     — 오늘 인증 결과 조회의 status 와 같은 값
     * @param progressRate 사이클 진행률(%). 확정된 인증 결과 기준
     */
    public record UpdatedChallenge(String challengeId, String todayStatus, java.math.BigDecimal progressRate) {}
}
