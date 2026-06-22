package com.ruleup.ruleup_backend.verification.evaluator;

import com.ruleup.ruleup_backend.verification.domain.VerificationConfig;
import com.ruleup.ruleup_backend.verification.signal.SyncSignal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 한 (멤버×날짜×챌린지)의 평가 입력. 오케스트레이터가 sync마다 만들어 평가기에 넘긴다.
 *  - signals: 이번 sync로 들어온 신호 전부(평가기가 자기 type만 골라 씀).
 *  - anchorFirstUnlockAt: windowAnchor(base=WAKE) 종속 창용으로, base를 먼저 평가해 채워둔 값(없으면 null).
 */
public record DayContext(
        LocalDate targetDate,        // KST 기준 대상일
        ZoneId zone,                 // MVP: Asia/Seoul
        Instant now,                 // 평가 시점
        VerificationConfig config,
        List<SyncSignal> signals,
        Instant anchorFirstUnlockAt
) {
    public DayContext withAnchor(Instant firstUnlockAt) {
        return new DayContext(targetDate, zone, now, config, signals, firstUnlockAt);
    }
}
