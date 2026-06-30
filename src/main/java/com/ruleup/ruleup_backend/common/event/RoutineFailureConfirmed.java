package com.ruleup.ruleup_backend.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "어떤 사용자가 특정 챌린지의 특정 날짜 루틴을 실패로 확정했다"는 도메인 이벤트.
 * verification(확정 배치·sync 제약 잠금)이 발행, watcher가 구독해 감시자 통지를 적재한다(§9/§11.4).
 * 모듈 간 직접 의존을 피하려 common에 둔다(§1 경계).
 *
 * @param challengeId 실패가 일어난 챌린지
 * @param userId      실패한 사용자(= 감시 대상. 보통 챌린지 생성자=감시자 inviter)
 * @param targetDate  실패 귀속 날짜(KST 하루 경계, §4.4)
 * @param confirmedAt 실패 확정 시각(야간 디퍼 판정 기준)
 */
public record RoutineFailureConfirmed(UUID challengeId, UUID userId, LocalDate targetDate, Instant confirmedAt) {}
