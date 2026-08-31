package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeTierResponse;
import com.ruleup.ruleup_backend.score.ScoreTransactionRepository;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.ScoreReason;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.score.domain.TierBands;
import com.ruleup.ruleup_backend.score.domain.UserScoreSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 내 티어 상세(GET /me/tier). 점수를 <b>계산하지 않고</b> 요약 테이블과 변동 원장을 읽어 조립만 한다 —
 * 승강급 판정은 티어 모듈 소관이다(Non-Goals).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeTierService {

    /** 최근 변동으로 내리는 건수 — API 계약 고정값. */
    private static final int RECENT_CHANGES = 10;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserScoreSummaryRepository summaryRepository;
    private final ScoreTransactionRepository transactionRepository;

    public MeTierResponse tier(UUID userId) {
        // 요약이 아직 없는 계정(구 데이터)은 가입 초기값과 같은 상태로 본다 — 빈 화면 대신 브론즈 10점.
        UserScoreSummary summary = summaryRepository.findById(userId)
                .orElseGet(() -> UserScoreSummary.initialize(userId));

        long score = summary.getTotalScore();
        Tier display = summary.getDisplayTier();

        return new MeTierResponse(
                summary.getActualTier().name(), score, display.name(),
                TierBands.isInGraceBand(score, display),
                promotion(summary.getActualTier(), score),
                demotion(display),
                recentChanges(userId));
    }

    /** 승급 안내는 <b>실제 티어</b> 기준이다 — 표시 티어가 유예로 남아 있어도 올라갈 곳은 실제 티어의 다음이다. */
    private MeTierResponse.Promotion promotion(Tier actual, long score) {
        Tier next = TierBands.next(actual);
        if (next == null) return null;   // 루비 — 더 올라갈 곳이 없다
        return new MeTierResponse.Promotion(next.name(), TierBands.pointsToPromote(score, next));
    }

    /** 강등 안내는 <b>표시 티어</b> 기준이다 — 유예 하한도 강등 확정선도 표시 티어의 시작점에서 나온다. */
    private MeTierResponse.Demotion demotion(Tier display) {
        if (!TierBands.hasDemotion(display)) return null;   // 브론즈 — 더 내려갈 티어가 없다
        return new MeTierResponse.Demotion(TierBands.graceFloor(display), TierBands.demoteAt(display));
    }

    private List<MeTierResponse.Change> recentChanges(UUID userId) {
        return transactionRepository.findRecent(userId, PageRequest.of(0, RECENT_CHANGES)).stream()
                .map(this::toChange)
                .toList();
    }

    /**
     * 저장 사건 → 화면 표기. 두 축이 다르다 — 저장은 무엇이 일어났는지(일일 성공·확정 미달·보너스…),
     * 표기는 사용자에게 뭐라고 부를지(사이클 성공·사이클 실패…)다.
     *
     * <p>{@code KICK_FAIL}(연속 실패 강퇴)은 여기서 나오지 않는다. 각 주의 루틴 점수에 이미
     * 반영돼 감점 이벤트 자체가 만들어지지 않기 때문이다.
     */
    private ScoreReason displayReason(ScoreTransaction t) {
        return switch (t.getReason()) {
            case DAILY_SUCCESS, STREAK_BONUS -> ScoreReason.CYCLE_SUCCESS;
            case CONFIRMED_MISS, STREAK_PENALTY -> ScoreReason.CYCLE_FAIL;
            case REVERSAL -> ScoreReason.APPEAL_RESTORE;
            case INCIDENT -> switch (t.getIncidentType()) {
                case CHEAT_DETECTED -> ScoreReason.CHEAT;
                case PERMISSION_KICK -> ScoreReason.KICK_PERMISSION;
                case VOLUNTARY_LEAVE -> ScoreReason.LEAVE;
            };
        };
    }

    private MeTierResponse.Change toChange(ScoreTransaction t) {
        LocalDate date = LocalDate.ofInstant(t.getCreatedAt(), KST);   // 화면은 KST 달력으로 읽는다
        return new MeTierResponse.Change(date.toString(), displayReason(t).name(),
                t.getChallengeId() != null ? t.getChallengeId().toString() : null, t.getAppliedDelta());
    }
}
