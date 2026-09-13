package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.common.event.CheatDetectionConfirmed;
import com.ruleup.ruleup_backend.score.domain.IncidentType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 부정행위 검출 확정 → <b>−50 감점</b> 집행 (공통 5-7 · 점수 정책 §4.8).
 *
 * <p>사건성 감점이라 사이클 순변동 ±20 한도를 거치지 않고 즉시 전액 반영한다 — 한도는 「한 주에
 * 얼마나 움직일 수 있나」를 다루는 장치인데 부정행위는 주간 성과가 아니기 때문이다.
 *
 * <p>검출 id 를 멱등 키로 넘긴다. 같은 검출이 두 번 전달돼도 −50 은 한 번만 반영된다.
 */
@Component
@RequiredArgsConstructor
public class CheatDetectionScoreListener {

    private static final Logger log = LoggerFactory.getLogger(CheatDetectionScoreListener.class);

    private final ScoreService scoreService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheatDetected(CheatDetectionConfirmed event) {
        try {
            scoreService.applyIncident(event.userId(), event.challengeId(),
                    IncidentType.CHEAT_DETECTED, event.detectionId().toString(), 0);
        } catch (RuntimeException e) {
            // 감점 실패가 검출 기록이나 강퇴를 되돌리지는 않는다. 운영자가 이력으로 확인한다.
            log.warn("부정행위 감점 실패 userId={} detectionId={} err={}",
                    event.userId(), event.detectionId(), e.toString());
        }
    }
}
