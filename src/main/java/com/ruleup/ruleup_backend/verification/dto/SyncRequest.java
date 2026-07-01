package com.ruleup.ruleup_backend.verification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.util.List;

/**
 * POST /sync 요청(전송 스펙 §0.1 envelope). Android SyncEnvelopeRequest 에 맞춘다.
 *  - deviceTimeMillis : 디바이스 시계(항상 전송) — 페이로드 검증 키로 사용.
 *  - signals          : 신호 배치(평가 입력).
 *  - permissions/network/gaps/diagnostics/integrity 등은 ignoreUnknown 으로 수신만(현재 미사용).
 *  - 멱등은 verification_daily/method_result upsert((member,date),(daily,method))로 이미 보장되므로
 *    별도 collectedAt 키가 없어도 안전하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncRequest(
        Long deviceTimeMillis,
        String bootSessionId,
        String timeZone,
        List<String> activeChallengeIds,
        List<SyncSignal> signals
) {}
