package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.common.event.RoutineFailureConfirmed;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 루틴 실패 확정 → 감시자 통지 적재(§9/§11.4).
 * 동기 @EventListener — verification 확정 트랜잭션 안에서 큐 적재가 원자적으로 일어난다
 * (적재는 DB만 건드리므로 트랜잭션 안에서 안전. 실제 외부 발송은 별도 스윕).
 * 통지 적재 실패가 인증 확정을 롤백시키지 않도록 예외는 삼킨다.
 */
@Component
@RequiredArgsConstructor
public class WatcherFailureListener {

    private static final Logger log = LoggerFactory.getLogger(WatcherFailureListener.class);

    private final WatcherNotificationService notificationService;

    @EventListener
    public void onRoutineFailure(RoutineFailureConfirmed event) {
        try {
            notificationService.enqueueForFailure(
                    event.challengeId(), event.userId(), event.targetDate(), event.confirmedAt());
        } catch (Exception e) {
            log.warn("감시자 통지 적재 실패 challengeId={} userId={}: {}",
                    event.challengeId(), event.userId(), e.getMessage());
        }
    }
}
