package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.challenge.lifecycle.*;
import com.ruleup.ruleup_backend.score.domain.*;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.domain.VerifiedVia;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Adapts confirmed source records to complete, replayable scoring requests. */
@Service @RequiredArgsConstructor
public class ScoreService {
    private final ScoreProcessor processor;
    private final ChallengeScoreInputs cycleInputs;
    private final ChallengeScoreSource challengeSource;
    private final VerificationDailyRepository daily;
    private final UserScoreSummaryRepository summaries;
    private final EntityManager em;
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public UserScoreSummary initialize(UUID user) {
        em.flush();processor.process(user,"signup",processor.inputHash(List.of()),List.of(),false);
        return summaries.findById(user).orElseThrow();
    }
    public void reconcileCycle(UUID user,UUID challenge,int no) { reconcile(user,challenge,no,false,null); }
    public void recompute(UUID user,UUID challenge,int no,UUID original) { reconcile(user,challenge,no,true,original); }
    private void reconcile(UUID user,UUID challenge,int no,boolean correction,UUID original) {
        var spec=processor.cycleSnapshot(user,challenge,no).or(()->cycleInputs.cycle(user,challenge,no));
        if(spec.isEmpty())return;
        List<ScoreInput> inputs=dailyInputs(user,challenge,spec.get());
        if(inputs.isEmpty())return;
        String hash=processor.inputHash(inputs);
        processor.process(user,(correction?"correction:"+original+":":"daily:")+hash,hash,inputs,correction);
    }
    private List<ScoreInput> dailyInputs(UUID user,UUID challenge,ScoreInput.CycleSpec spec) {
        List<ScoreInput> inputs=new ArrayList<>();
        for(var d:daily.findByUserIdAndChallengeIdAndTargetDateBetween(user,challenge,spec.startOn(),spec.endOn())) {
            if(d.isPending() || !spec.eligibleDates().contains(d.getTargetDate()) || d.getVerifiedVia()==VerifiedVia.MANUAL)continue;
            String result=d.getStatus().name();
            if(!Set.of("SUCCESS","FAILED").contains(result))result="INVALID";
            var old=processor.original(user,ScoreInput.Kind.DAILY,d.getId().toString());
            var input=new ScoreInput(ScoreInput.Kind.DAILY,d.getId().toString(),Math.toIntExact(d.getVersion()),
                    old.map(ScoreInput::effectiveAt).orElse(d.getTargetDate().atStartOfDay(KST).toInstant()),"AUTO",spec,d.getTargetDate(),result,null,challenge,0,0,false);
            inputs.add(input);
        }
        return inputs;
    }
    public void closeCycle(UUID user,UUID challenge,int no) {
        var spec=processor.cycleSnapshot(user,challenge,no).or(()->cycleInputs.cycle(user,challenge,no));
        if(spec.isEmpty())return;
        var c=spec.get();List<ScoreInput> inputs=dailyInputs(user,challenge,c);
        if(inputs.size()!=c.eligibleDates().size())throw new IllegalStateException("SCORE_DATES_NOT_FINAL");
        inputs.add(new ScoreInput(ScoreInput.Kind.CLOSE,c.cycleId().toString(),1,c.closesAt(),"AUTO",c,null,null,null,challenge,0,0,false));
        // The close source is stable; the complete input hash includes every finalized date.
        String hash=processor.inputHash(inputs);processor.process(user,"close:"+hash,hash,inputs,false);
    }
    public void applyIncident(UUID user,UUID challenge,IncidentType type,String source,int weeks) {
        var old=processor.original(user,ScoreInput.Kind.INCIDENT,type+":"+source);
        ScoreInput i=old.orElseGet(()->new ScoreInput(ScoreInput.Kind.INCIDENT,type+":"+source,1,Instant.now(),
                challengeSource.findById(challenge).map(c->c.automatic()?"AUTO":"MANUAL").orElseThrow(()->new IllegalStateException("SCORE_INCIDENT_SOURCE_MISSING")),
                null,null,null,type,challenge,type.deduction(weeks),weeks,type.deduction(weeks)==0));
        submitIncident(user,i);
    }
    public void submitIncident(UUID user,ScoreInput input) {
        String hash=processor.inputHash(List.of(input));processor.process(user,"incident:"+input.sourceId()+":"+input.sourceVersion(),hash,List.of(input),false);
    }
}
