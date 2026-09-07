package com.ruleup.ruleup_backend.me.service;

import com.ruleup.ruleup_backend.me.dto.MeTierResponse;
import com.ruleup.ruleup_backend.score.domain.ScoreReason;
import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
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

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 저장 사건 → 화면 표기. 두 축이 다르다 — 저장은 무엇이 일어났는지(일일 성공·확정 미달·보너스…),
     * 표기는 사용자에게 뭐라고 부를지(사이클 성공·사이클 실패…)다.
     *
     * <p>{@code KICK_FAIL}(연속 실패 강퇴)은 여기서 나오지 않는다. 각 주의 루틴 점수에 이미
     * 반영돼 감점 이벤트 자체가 만들어지지 않기 때문이다.
     */
    public ScoreReason displayReason(ScoreTransaction t) {
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
