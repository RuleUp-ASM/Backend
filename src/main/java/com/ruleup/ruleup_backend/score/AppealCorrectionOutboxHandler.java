package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeCycle;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.outbox.OutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 이의 인용 → <b>점수 소급 정정</b> (공통 5-7 「이의 인용에 따른 정정」). <b>아웃박스로 받는다.</b>
 *
 * <p>예전에는 커밋 이후 인메모리 이벤트로 받고, 실패하면 로그만 남기고 삼켰다. 그러면 사용자에게는
 * "인용됐다"고 응답해 놓고 <b>점수는 끝내 돌아오지 않는 상태</b>가 영구히 남는다 — 재처리할 근거가
 * 어디에도 없다. 스펙이 "모듈 간 정정 전달이 실패한 경우 재처리 가능한 상태를 남긴다"고 적은 지점이다.
 *
 * <p>발행 의사를 인용과 같은 커밋에 적어 두면, 재계산이 몇 번 실패하든 스윕이 다시 집는다.
 * 정정은 사이클 재계산이라 몇 번 돌려도 같은 값에 수렴한다(멱등).
 *
 * <p><b>예외를 삼키지 않는다.</b> 던져야 백오프 재시도가 걸린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppealCorrectionOutboxHandler implements OutboxHandler {

    /** 아웃박스 라우팅 키. */
    public static final String OUTBOX_TYPE = "APPEAL_SCORE_CORRECTION";

    private final ScoreService scoreService;
    private final ChallengeRepository challengeRepository;

    public record Payload(String userId, String challengeId, String verificationId, String targetDate) {}

    @Override
    public String type() {
        return OUTBOX_TYPE;
    }

    @Override
    public void handle(String payload) {
        Payload event = OutboxService.parse(payload, Payload.class);
        UUID challengeId = UUID.fromString(event.challengeId());

        Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
        if (challenge == null || challenge.getStartDate() == null) {
            // 방이 사라졌으면 재계산할 사이클도 없다. 재시도해도 달라지지 않으므로 종료로 본다.
            log.info("이의 정정 대상 챌린지가 없다 — 재계산을 건너뛴다. challengeId={}", challengeId);
            return;
        }

        LocalDate targetDate = LocalDate.parse(event.targetDate());
        long elapsed = ChronoUnit.DAYS.between(challenge.getStartDate(), targetDate);
        if (elapsed < 0) return;   // 시작 전 날짜 — 사이클에 속하지 않는다
        int cycleNo = (int) (elapsed / ChallengeCycle.CYCLE_DAYS) + 1;

        scoreService.recompute(UUID.fromString(event.userId()), challengeId, cycleNo,
                UUID.fromString(event.verificationId()));
        log.info("이의 인용 점수 정정 userId={} challengeId={} cycle={}", event.userId(), challengeId, cycleNo);
    }
}
