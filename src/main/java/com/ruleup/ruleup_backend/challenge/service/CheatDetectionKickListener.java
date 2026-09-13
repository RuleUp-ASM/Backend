package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.common.event.CheatDetectionConfirmed;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 부정행위 검출 확정 → <b>해당 챌린지 강퇴·영구 차단</b> 집행 (공통 5-7).
 *
 * <p>판정은 인증 모듈이 하고 여기는 집행만 한다. 강퇴 3종 중 유일하게 백오프가 아니라 영구
 * 차단이며, 필수(A) 통지도 그 경로가 함께 낸다.
 *
 * <p><b>커밋 이후에 집행한다.</b> 검출 기록이 커밋되지 않았는데 강퇴가 나가면, 기록 없는 강퇴가
 * 남아 유저에게 근거를 설명할 수 없다. 반대로 집행이 실패해도 기록은 남아 운영자가 이어서
 * 처리할 수 있다 — 경고 로그가 그 단서다.
 */
@Component
@RequiredArgsConstructor
public class CheatDetectionKickListener {

    private static final Logger log = LoggerFactory.getLogger(CheatDetectionKickListener.class);

    private final RoomAdminService roomAdminService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCheatDetected(CheatDetectionConfirmed event) {
        try {
            roomAdminService.kickForCheat(event.challengeId(), event.userId());
        } catch (RuntimeException e) {
            // 집행이 실패해도 검출 기록은 남는다. 운영자가 이력을 보고 이어서 처리한다.
            log.warn("부정행위 강퇴 집행 실패 userId={} challengeId={} err={}",
                    event.userId(), event.challengeId(), e.toString());
        }
    }
}
