package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeTierResponse;
import com.ruleup.ruleup_backend.score.domain.ScoreLedgerReason;
import com.ruleup.ruleup_backend.score.domain.ScoreReason;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 점수 원장 1행 → 화면 표기 1줄.
 *
 * <p>내 티어의 「최근 변동 10건」과 「전체 보기」가 <b>같은 데이터를 더 보는 것</b>일 뿐이므로
 * 표기 규칙이 갈리면 안 된다. 같은 행이 두 화면에서 다른 사유로 보이면 사용자는 둘 중 어느
 * 쪽이 맞는지 알 방법이 없다.
 */
@Component
public class ScoreChangeView {

    private static final Logger log = LoggerFactory.getLogger(ScoreChangeView.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 그릴 수 있는 행인가. {@code INCIDENT} 인데 {@code incident_type} 이 비어 있으면 <b>무슨 사건인지
     * 알 수 없어</b> 표기를 만들 수 없다 — 이런 행은 목록에서 뺀다.
     *
     * <p>정상 쓰기 경로로는 나올 수 없는 행이다({@code ScoreCalculator.validate} 가 AUTO 입력에
     * incident_type 을 강제하고, MANUAL 입력은 애초에 원장에 남지 않는다). 그런데 손으로 만진 행이나
     * 레거시 행이 하나만 섞여도 {@link #displayReason} 의 switch 가 NPE 로 터지면서 <b>내 티어 화면
     * 전체가 500</b> 이 됐다(QA 2026-09-16). 한 행의 손상이 화면을 통째로 죽이지 않게 한다.
     *
     * <p>사유를 추측해 채우지는 않는다. 부정행위 감점을 「자진 탈퇴」로 보여주는 것은 빈칸보다 나쁘다.
     * 대신 경고를 남겨 손상된 행을 운영이 찾아갈 수 있게 한다.
     */
    public boolean renderable(ScoreTransaction t) {
        if (t.getReason() == ScoreLedgerReason.INCIDENT && t.getIncidentType() == null) {
            log.warn("score_change_unrenderable transactionId={} userId={} reason=INCIDENT incidentType=null",
                    t.getId(), t.getUserId());
            return false;
        }
        return true;
    }

    /**
     * 저장 사건 → 화면 표기. 두 축이 다르다 — 저장은 무엇이 일어났는지(일일 성공·확정 미달·보너스…),
     * 표기는 사용자에게 뭐라고 부를지(사이클 성공·사이클 실패…)다.
     *
     * <p>{@code KICK_FAIL}(연속 실패 강퇴)은 여기서 나오지 않는다. 각 주의 루틴 점수에 이미
     * 반영돼 감점 이벤트 자체가 만들어지지 않기 때문이다.
     */
    public ScoreReason displayReason(ScoreTransaction t) {
        return switch (t.getReason()) {
            case SIGNUP, CYCLE_CLOSED, PROCESSING_COMMIT, CORRECTION_COMMIT, DAILY_SUCCESS, STREAK_BONUS -> ScoreReason.CYCLE_SUCCESS;
            case CONFIRMED_MISS, STREAK_PENALTY -> ScoreReason.CYCLE_FAIL;
            // 되감기는 방향을 봐야 한다. 점수를 되돌려 준 되감기(감점 취소)만 「이의 복원」이고,
            // 성공을 거둬들인 되감기는 사용자에게 점수가 깎인 일이다 — 전부 APPEAL_RESTORE 로
            // 부르면 화면에 「이의 복원 -1」 같은 문장이 나온다(QA TIER-05).
            case REVERSAL -> t.getAppliedDelta() < 0 ? ScoreReason.CYCLE_FAIL : ScoreReason.APPEAL_RESTORE;
            case INCIDENT -> switch (t.getIncidentType()) {
                case CHEAT_DETECTED -> ScoreReason.CHEAT;
                case PERMISSION_KICK -> ScoreReason.KICK_PERMISSION;
                case VOLUNTARY_LEAVE -> ScoreReason.LEAVE;
            };
        };
    }

    /**
     * @param titles 챌린지 id → 이름. 없으면 null 로 두고 화면이 사유만 그린다 —
     *               삭제된 방의 이름은 복원할 방법이 없는 경우가 있다.
     */
    public MeTierResponse.Change toChange(ScoreTransaction t, Map<UUID, String> titles) {
        LocalDate date = LocalDate.ofInstant(t.getCreatedAt(), KST);   // 화면은 KST 달력으로 읽는다
        UUID challengeId = t.getChallengeId();
        return new MeTierResponse.Change(
                date.toString(),
                displayReason(t).name(),
                challengeId != null ? challengeId.toString() : null,
                challengeId != null ? titles.get(challengeId) : null,
                t.getAppliedDelta());
    }
}
