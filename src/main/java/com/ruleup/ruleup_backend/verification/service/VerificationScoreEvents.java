package com.ruleup.ruleup_backend.verification.service;

import com.ruleup.ruleup_backend.challenge.lifecycle.*;
import com.ruleup.ruleup_backend.common.outbox.*;
import com.ruleup.ruleup_backend.score.*;
import com.ruleup.ruleup_backend.score.service.*;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.time.*;
import java.util.*;

/** Capture eligibility at judgement time, before settings or membership can change. */
@lombok.extern.slf4j.Slf4j
@Component @RequiredArgsConstructor
public class VerificationScoreEvents {
    public record Confirmed(VerificationDaily daily) {}
    private final EntityManager em;
    private final ChallengeScoreSource source;
    private final ChallengeScoreInputs cycles;
    private final ScoreProcessor processor;
    private final OutboxService outbox;
    private final OutboxDispatcher dispatcher;
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void record(Confirmed event) {
        var d=event.daily();if(d.isPending() || d.hasInvalidFailure() || d.getVerifiedVia()==com.ruleup.ruleup_backend.verification.domain.VerifiedVia.MANUAL)return;
        em.flush();
        var saved=processor.original(d.getUserId(),ScoreInput.Kind.DAILY,d.getId().toString());
        var c=source.findById(d.getChallengeId());
        if(saved.isEmpty() && (c.isEmpty()||!c.get().automatic()))return;
        int no=saved.map(i->i.cycle().cycleNo()).orElseGet(()->(int)(java.time.temporal.ChronoUnit.DAYS.between(c.get().getStartDate(),d.getTargetDate())/7)+1);
        Optional<ScoreInput.CycleSpec> spec;
        try {
            spec=saved.map(ScoreInput::cycle).or(()->processor.cycleSnapshot(d.getUserId(),d.getChallengeId(),no)).or(()->cycles.cycle(d.getUserId(),d.getChallengeId(),no));
        } catch(RuntimeException e) {
            // Missing cycle inputs (membership, eligible dates) must not undo the judgement itself: thrown here, before
            // commit, it rolled the finalization back and the day stayed PENDING, retried forever (QA TIER-15 B4).
            // The judgement commits; ScoreSyncService re-derives the score input once the inputs resolve.
            log.warn("score_input_deferred dailyId={} userId={} cycleNo={} err={}",d.getId(),d.getUserId(),no,e.toString());
            return;
        }
        if(spec.isEmpty() || !spec.get().eligibleDates().contains(d.getTargetDate()))return;
        String status=d.getStatus().name();if(!Set.of("SUCCESS","FAILED").contains(status))status="INVALID";
        ScoreInput input=new ScoreInput(ScoreInput.Kind.DAILY,d.getId().toString(),Math.toIntExact(d.getVersion()),
                saved.map(ScoreInput::effectiveAt).orElse(d.getTargetDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),"AUTO",spec.get(),d.getTargetDate(),status,null,d.getChallengeId(),0,0,false);
        outbox.enqueue(ScoreInputOutboxHandler.TYPE,new ScoreInputOutboxHandler.Payload(d.getUserId(),input),"score-input:"+d.getId()+":"+d.getVersion());
        dispatcher.requestFlush();
    }
}
