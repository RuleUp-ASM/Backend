package com.ruleup.ruleup_backend.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * "이상패턴 탐지로 부정행위가 확정됐다"는 도메인 이벤트 (인증 공통 5-7).
 *
 * <p>인증 모듈은 <b>신호를 내는 데까지만 책임진다.</b> 집행은 각 도메인이 맡는다 —
 * 방 내부가 강퇴·영구 차단을, 티어·점수가 −50 을, 알림이 필수(A) 통지를 한다.
 * 이 경계를 이벤트로 고정해 두지 않으면 같은 집행이 여러 곳에 복제된다.
 *
 * <p><b>누적 카운트가 없다.</b> 이 이벤트 1건이 곧 확정이며, 수신측은 "몇 번째인가"를 세지 않는다.
 *
 * @param userId         검출된 사용자
 * @param challengeId    집계 단위 — 영구 차단은 계정이 아니라 이 방에 걸린다
 * @param verificationId 검출로 무효가 된 판정. 감사의 조인 키다
 * @param detectionId    검출 기록 id — 점수 감점의 멱등 키로 쓴다
 * @param detectedAt     확정 시각
 */
public record CheatDetectionConfirmed(UUID userId, UUID challengeId, UUID verificationId,
                                      UUID detectionId, Instant detectedAt) {}
