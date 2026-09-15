package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.service.ScoreCalculator;
import com.ruleup.ruleup_backend.score.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ScoreCalculatorTest {
    @Test void sameLogicalInstantUpdatesDisplayOnlyAfterItsLastInput() {
        var c=ScoreReplayIT.cycle(UUID.randomUUID(),1);
        var incident=new ScoreInput(ScoreInput.Kind.INCIDENT,"leave",1,c.startsAt(),"AUTO",null,null,null,
                IncidentType.VOLUNTARY_LEAVE,c.challengeId(),-15,0,false);
        var a=ScoreReplayIT.daily(c,"SUCCESS",1);
        var first=ScoreCalculator.replay(List.of(a,incident),95,Tier.BRONZE);
        var reversed=ScoreCalculator.replay(List.of(incident,a),95,Tier.BRONZE);
        assertThat(first.score()).isEqualTo(90);
        assertThat(first.display()).isEqualTo(Tier.BRONZE);
        assertThat(reversed.results()).isEqualTo(first.results());
    }
    @Test void invalidKeepsStreakAndOpenPredecessorDefersClose() {
        UUID challenge=UUID.randomUUID();var a=ScoreReplayIT.cycle(challenge,1);var b=ScoreReplayIT.cycle(challenge,2);
        var inputs=List.of(ScoreReplayIT.daily(a,"SUCCESS",1),ScoreReplayIT.close(a),ScoreReplayIT.daily(b,"INVALID",1),ScoreReplayIT.close(b));
        var result=ScoreCalculator.replay(inputs,10,Tier.BRONZE);
        assertThat(result.cycles().get(b.cycleId()).result).isEqualTo(CycleResult.INVALID);
        assertThat(result.cycles().get(b.cycleId()).successStreak).isEqualTo(1);
        assertThat(result.score()).isEqualTo(20);
        assertThatThrownBy(()->ScoreCalculator.replay(List.of(inputs.get(0),inputs.get(2),inputs.get(3)))).hasMessage("SCORE_PREVIOUS_CYCLE_OPEN");
    }
    @Test void malformedIncidentCannotClaimAnExemptionOrUseCycleLimit() {
        var c=ScoreReplayIT.cycle(UUID.randomUUID(),1);
        var invalid=new ScoreInput(ScoreInput.Kind.INCIDENT,"cheat",1,c.startsAt(),"AUTO",null,null,null,
                IncidentType.CHEAT_DETECTED,c.challengeId(),0,0,true);
        assertThatThrownBy(()->ScoreCalculator.replay(List.of(invalid))).hasMessage("SCORE_INCIDENT_POLICY_MISMATCH");
    }
}
