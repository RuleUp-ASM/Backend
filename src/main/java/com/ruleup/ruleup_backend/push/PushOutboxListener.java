package com.ruleup.ruleup_backend.push;

import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 실시간 권한공백 감지 → 고스트 푸시 큐 적재(§8.5).
 * 동기 @EventListener — sync 평가 트랜잭션 안에서 큐 적재가 원자적으로 일어난다(적재는 DB만 건드림).
 * 실제 발송은 별도 스윕({@link PushOutboxDispatcher}). 적재 실패가 sync 를 롤백시키지 않도록 예외는 삼킨다.
 */
@Component
@RequiredArgsConstructor
public class PushOutboxListener {

    private static final Logger log = LoggerFactory.getLogger(PushOutboxListener.class);

    private final PushOutboxService pushOutboxService;

    @EventListener
    public void onPermissionGap(PermissionGapDetected event) {
        try {
            pushOutboxService.enqueuePermissionGap(
                    event.userId(), event.challengeId(), event.targetDate(),
                    event.signalType(), event.detectedAt());
        } catch (Exception e) {
            log.warn("고스트 푸시 적재 실패 userId={} challengeId={}: {}",
                    event.userId(), event.challengeId(), e.getMessage());
        }
    }
}
