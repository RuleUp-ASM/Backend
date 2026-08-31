package com.ruleup.ruleup_backend.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "어떤 사용자가 특정 챌린지의 특정 날짜 루틴을 실패로 확정했다"는 도메인 이벤트.
 * verification(확정 배치·sync 제약 잠금)이 발행, watcher가 구독해 감시자 통지를 적재한다(§9/§11.4).
 * 모듈 간 직접 의존을 피하려 common에 둔다(§1 경계).
 *
 * <p>발행 시점이 곧 <b>이의 기간 종료 후</b>다 — 귀속일+2일 00:00 KST 확정 배치에서만 나오고,
 * 이의가 인용된 건은 애초에 발행되지 않는다. 수신측이 시각을 스스로 추정하지 않아도 되는 이유이며,
 * 감시자 모듈의 "이의 기간 종료 전 발송 0건" 가드레일이 여기에 달려 있다.
 *
 * @param challengeId    실패가 일어난 챌린지
 * @param userId         실패한 사용자(= 감시 대상)
 * @param verificationId 근거가 된 판정 건. <b>감사의 조인 키</b>다 — 통지 시각을 이 건의 확정
 *                       시각과 대조해 조기 발송 0건을 확인한다
 * @param targetDate     실패 귀속 날짜(KST 하루 경계)
 * @param confirmedAt    실패 확정 시각
 */
public record RoutineFailureConfirmed(UUID challengeId, UUID userId, UUID verificationId,
                                      LocalDate targetDate, Instant confirmedAt) {}
