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
 * @param consentRequired    개별 동의가 없어 <b>수집을 거부한</b> 신호가 요구하는 동의 항목
 *                           (LOCATION_INFO · HEALTH_INFO). 비어 있지 않으면 클라는 동의 화면으로 보낸다.
 *                           요청 전체를 반려하지 않는 이유는, 동의가 필요 없는 앱 사용 인증까지 함께
 *                           멈추기 때문이다
 */
public record SyncResponse(
        String syncedAt,
        int flushIntervalSec,
        List<UpdatedChallenge> updatedChallenges,
        List<String> ignoredSignalTypes,
        int maxPayloadBytes,
        int dedupDroppedCount,
        List<String> consentRequired
) {
    /**
     * @param challengeId  챌린지 ID
     * @param todayStatus  IN_PROGRESS / FAIL_EXPECTED / DONE / FAILED / NOT_TARGET — 상태값 4종 + 비대상
     *                     — 오늘 인증 결과 조회의 status 와 같은 값
     * @param progressRate 사이클 진행률(%). 확정된 인증 결과 기준
     */
    public record UpdatedChallenge(String challengeId, String todayStatus, java.math.BigDecimal progressRate) {}
}
