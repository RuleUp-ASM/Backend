package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 한 (멤버×날짜×챌린지)의 평가 입력. 오케스트레이터가 sync마다 만들어 평가기에 넘긴다.
 *  - signals: 이번 sync로 들어온 신호 전부(델타). 평가기가 자기 type만 골라 씀.
 *  - priorEvidence: 직전까지 누적된 method_result.evidence(없으면 null).
 *    신호가 델타라, 평가기는 prior + 이번 신호를 합쳐 누적 판정한다(예: WAKE firstUnlockAt = min).
 *  - anchorFirstUnlockAt: windowAnchor(base=WAKE) 종속 창용 앵커(없으면 null).
 */
public record DayContext(
        LocalDate targetDate,
        ZoneId zone,
        Instant now,
        VerificationConfig config,
        List<SyncSignal> signals,
        Map<String, Object> priorEvidence,
        Instant anchorFirstUnlockAt
) {
    public DayContext withAnchor(Instant firstUnlockAt) {
        return new DayContext(targetDate, zone, now, config, signals, priorEvidence, firstUnlockAt);
    }
}
