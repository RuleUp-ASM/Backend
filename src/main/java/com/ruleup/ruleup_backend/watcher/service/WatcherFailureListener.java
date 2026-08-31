package com.ruleup.ruleup_backend.watcher.service;

import com.ruleup.ruleup_backend.common.event.RoutineFailureConfirmed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 실패 확정 이벤트 구독 → 감시자 통지.
 *
 * <p>이 리스너가 <b>이의 기간 가드레일의 실행부</b>다. 스스로 시각을 판단하지 않고 인증 모듈이
 * 확정 시점에 발행한 이벤트만 받는다 — 여기서 "확정됐을 것 같은" 건을 추정하기 시작하면
 * 조기 발송이 생긴다.
 *
 * <p>통지 실패가 인증 확정을 롤백시키지 않도록 예외는 삼킨다. 놓친 건은 보정 배치가 줍는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatcherFailureListener {

    private final WatcherNoticeService noticeService;

    @EventListener
    public void onRoutineFailure(RoutineFailureConfirmed event) {
        try {
            noticeService.onFailureConfirmed(event.challengeId(), event.userId(),
                    event.verificationId(), event.targetDate(), event.confirmedAt());
        } catch (Exception e) {
            log.warn("감시자 통지 실패 challengeId={} userId={}: {}",
                    event.challengeId(), event.userId(), e.toString());
        }
    }
}
