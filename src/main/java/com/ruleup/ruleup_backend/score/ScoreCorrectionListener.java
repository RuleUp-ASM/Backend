package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.verification.service.AppealService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.temporal.ChronoUnit;

/**
 * 이의 인용 → 점수 소급 정정.
 *
 * <p><b>커밋 이후</b>에 돈다. 판정 정정({@code FAILED → DONE})이 확정된 다음이라야 재계산이 새 원본을
 * 읽는다. 그리고 재계산이 실패해도 인용 자체는 되돌아가면 안 된다 — 사용자에게 이미 "인용됐다"고
 * 응답했기 때문이다. 실패는 로그로 남기고 정합성 검사가 수습한다.
 */
@Component
@RequiredArgsConstructor
public class ScoreCorrectionListener {

    private static final Logger log = LoggerFactory.getLogger(ScoreCorrectionListener.class);

    private final ScoreService scoreService;
    private final ChallengeRepository challengeRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppealAccepted(AppealService.AppealAccepted event) {
        Challenge challenge = challengeRepository.findById(event.challengeId()).orElse(null);
        if (challenge == null || challenge.getStartDate() == null) return;

        long elapsed = ChronoUnit.DAYS.between(challenge.getStartDate(), event.targetDate());
        if (elapsed < 0) return;
        int cycleNo = (int) (elapsed / ChallengeCycle.CYCLE_DAYS) + 1;

        try {
            scoreService.recompute(event.userId(), event.challengeId(), cycleNo,
                    event.verificationDailyId());
        } catch (RuntimeException e) {
            log.error("이의 인용 점수 정정 실패: user={} challenge={} cycle={} verification={}",
                    event.userId(), event.challengeId(), cycleNo, event.verificationDailyId(), e);
        }
    }
}
