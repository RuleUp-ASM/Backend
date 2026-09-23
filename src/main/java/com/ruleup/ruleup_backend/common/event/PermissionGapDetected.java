package com.ruleup.ruleup_backend.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "셋업 완료(READY) 멤버가 특정 신호의 권한을 회수해 sync 에 비회복 PERMISSION_MISSING 공백을 보냈다"는
 * 도메인 이벤트. verification(sync 평가)이 발행, push 모듈이 구독해 고스트 푸시 큐를 적재한다(§8.5).
 * 모듈 간 직접 의존을 피하려 common 에 둔다(§1 경계).
 *
 * @param userId      권한 공백이 감지된 사용자
 * @param challengeId 대상 챌린지
 * @param signalType  권한이 빠진 신호(= method 이름, 클라가 어떤 권한을 재요청할지 분기용)
 * @param targetDate  귀속 날짜(KST) — 하루 1건 멱등 키
 * @param detectedAt  감지 시각
 */
public record PermissionGapDetected(UUID userId, UUID challengeId, String signalType,
                                    LocalDate targetDate, Instant detectedAt) {}
