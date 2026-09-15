package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.challenge.lifecycle.ScoreStateOutboxHandler;
import com.ruleup.ruleup_backend.common.outbox.*;
import com.ruleup.ruleup_backend.score.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.ruleup.ruleup_backend.score.ScoreKeys.*;

/** One user lock, append-only results/reversals/completion, and atomic current projections/outbox. */
@Service @RequiredArgsConstructor
public class ScoreProcessor {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OutboxService outbox;
    private final OutboxDispatcher dispatcher;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    @Value("${app.score.max-replay-inputs:20000}") private int maxReplayInputs;
    public record Completion(String inputHash, List<ScoreInput> inputs, int score, Tier actualTier,
                             Tier displayTier, long stateVersion, Instant affectedFrom) {}
    public record Stored(ScoreInput input, String cycleResult, Integer successStreakAfter, Integer failureStreakAfter) {}
    public record Checkpoint(int score,Tier displayTier,Instant effectiveAt,String verificationHash) {}
    private Checkpoint checkpoint(UUID user) {
        return jdbc.query("SELECT payload_json FROM score_transactions WHERE user_id=? AND entry_kind='COMMIT' AND source_type='CHECKPOINT' ORDER BY effective_at DESC LIMIT 1",
                (rs,n)->json.readValue(rs.getString(1),Checkpoint.class),bytes(user)).stream().findFirst().orElse(null);
    }
    private record Row(UUID id, ScoreInput input, int raw, int limited, int applied, int balance, Tier actual, Tier display) {}
    public String inputHash(List<ScoreInput> inputs) { return hash(json.writeValueAsString(inputs.stream().sorted(ScoreCalculator.ORDER).toList())); }

    private void validateCheckpoint(UUID user, Checkpoint checkpoint) {
        if(checkpoint==null)return;
        if(checkpoint.effectiveAt()==null || checkpoint.displayTier()==null || checkpoint.score()<0 || checkpoint.score()>2000
                || !Objects.equals(checkpoint.verificationHash(),hash("SCORE_BOUNDARY",user,checkpoint.score(),checkpoint.displayTier(),checkpoint.effectiveAt())))
            throw new IllegalStateException("SCORE_UNVERIFIED_BOUNDARY");
        // This compact boundary supports accounts with no prior cycle dependency only. Never invent missing streak/limit state.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_score_states WHERE user_id=? AND cycle_start_on<?",Integer.class,bytes(user),checkpoint.effectiveAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate())>0)
            throw new IllegalStateException("SCORE_CHECKPOINT_CYCLE_BASELINE_MISSING");
    }

    /** Compare stored state with a deterministic replay while holding the same user lock as writers. */
    @Transactional(timeout=30)
    public Set<String> verifyUser(UUID user) {
        var summary=jdbc.queryForMap("SELECT total_score,actual_tier,display_tier FROM user_score_summaries WHERE user_id=? FOR UPDATE",bytes(user));
        var boundary=checkpoint(user);validateCheckpoint(user,boundary);
        var rows=load(user).stream().filter(r->boundary==null||!r.input().effectiveAt().isBefore(boundary.effectiveAt())).toList();
        if(rows.size()>maxReplayInputs)throw new IllegalStateException("SCORE_REPLAY_LIMIT_EXCEEDED");
        var projected=ScoreCalculator.replay(rows.stream().map(Row::input).toList(),boundary==null?0:boundary.score(),boundary==null?Tier.BRONZE:boundary.displayTier());
        Set<String> errors=new TreeSet<>();
        if(((Number)summary.get("total_score")).intValue()!=projected.score() || !summary.get("actual_tier").equals(projected.actual().name()) || !summary.get("display_tier").equals(projected.display().name()))errors.add("SUMMARY");
        Map<String,Row> bySource=new HashMap<>();
        for(var row:rows)if(bySource.put(source(user,row.input()),row)!=null)errors.add("DUPLICATE_SOURCE");
        for(var value:projected.results()) {
            var row=bySource.get(source(user,value.input()));
            if(row.raw()!=value.raw()||row.limited()!=value.limited()||row.applied()!=value.applied()||row.balance()!=value.balance()||row.actual()!=value.actual()||row.display()!=value.display())errors.add("LEDGER");
        }
        var states=jdbc.queryForList("SELECT * FROM cycle_score_states WHERE user_id=?",bytes(user));
        if(states.size()!=projected.cycles().size())errors.add("CYCLE_COUNT");
        for(var state:states) {
            var c=projected.cycles().get(uuid((byte[])state.get("cycle_id")));
            if(c==null){errors.add("CYCLE_MISSING_INPUT");continue;}
            if(((Number)state.get("raw_cumulative")).intValue()!=c.raw || ((Number)state.get("limited_cumulative")).intValue()!=c.limited
                    || ((Number)state.get("success_count")).intValue()!=c.success || ((Number)state.get("miss_count")).intValue()!=c.miss
                    || !state.get("tier_snapshot").equals(c.tier.name()) || !Objects.equals(state.get("cycle_result"),c.result==null?null:c.result.name())
                    || !Objects.equals(Objects.toString(state.get("success_streak_after"),null),c.closedAt==null?null:Integer.toString(c.successStreak))
                    || !Objects.equals(Objects.toString(state.get("failure_streak_after"),null),c.closedAt==null?null:Integer.toString(c.failureStreak)))errors.add("CYCLE_STATE");
        }
        return errors;
    }

    @Transactional(timeout=30)
    public Completion process(UUID user, String requestKey, String requestHash, List<ScoreInput> inputs, boolean correction) {
        long started=System.nanoTime();
        try {
        if(requestKey==null || inputs==null || !inputHash(inputs).equals(requestHash))throw new IllegalArgumentException("SCORE_INPUT_HASH_MISMATCH");
        // INSERT IGNORE holds a shared duplicate-key lock, which can deadlock on a concurrent upgrade.
        jdbc.update("INSERT INTO user_score_summaries (user_id,total_score,actual_tier,display_tier) VALUES (?,10,'BRONZE','BRONZE') ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)",bytes(user));
        long lockStarted=System.nanoTime();
        var summary=jdbc.queryForMap("SELECT total_score,display_tier,version FROM user_score_summaries WHERE user_id=? FOR UPDATE",bytes(user));
        metrics.timer("score.user.lock.wait").record(System.nanoTime()-lockStarted,java.util.concurrent.TimeUnit.NANOSECONDS);
        String processing=hash(user,requestKey),commitKey=hash("COMMIT",user,processing);
        var completed=jdbc.query("SELECT payload_json FROM score_transactions WHERE idempotency_key=?",(rs,n)->json.readValue(rs.getString(1),Completion.class),commitKey);
        if(!completed.isEmpty()) {
            if(!completed.getFirst().inputHash().equals(requestHash))throw new IllegalArgumentException("SCORE_PROCESSING_KEY_CONFLICT");
            return completed.getFirst();
        }
        var checkpoint=checkpoint(user);
        validateCheckpoint(user,checkpoint);
        if(checkpoint!=null && inputs.stream().anyMatch(i->i.effectiveAt().isBefore(checkpoint.effectiveAt())))throw new IllegalStateException("SCORE_BEFORE_VERIFIED_BOUNDARY");
        var old=load(user).stream().filter(r->checkpoint==null||!r.input().effectiveAt().isBefore(checkpoint.effectiveAt())).toList();
        Map<String,Row> originals=new HashMap<>();
        Map<String,ScoreInput> current=new HashMap<>();
        for(var row:old) { String source=source(user,row.input());originals.put(source,row);current.put(source,row.input()); }
        Instant affected=null;
        for(var i:inputs) {
            ScoreCalculator.validate(i);
            String key=source(user,i);ScoreInput previous=current.get(key);
            if(previous!=null) {
                if(i.sourceVersion()<previous.sourceVersion())continue;
                if(i.sourceVersion()==previous.sourceVersion()) {
                    if(!i.equals(previous))throw new IllegalArgumentException("SCORE_SOURCE_VERSION_CONFLICT");
                    continue;
                }
                if(!i.effectiveAt().equals(previous.effectiveAt())||i.kind()!=previous.kind()||!Objects.equals(i.cycle(),previous.cycle()))
                    throw new IllegalArgumentException("SCORE_ORIGINAL_POSITION_CHANGED");
            }
            current.put(key,i);
            if(affected==null||i.effectiveAt().isBefore(affected))affected=i.effectiveAt();
        }
        if(checkpoint==null && current.values().stream().noneMatch(i->i.kind()==ScoreInput.Kind.SIGNUP)) {
            Instant joined=jdbc.queryForObject("SELECT created_at FROM users WHERE id=?",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC),bytes(user));
            var signup=new ScoreInput(ScoreInput.Kind.SIGNUP,user.toString(),1,joined,"AUTO",null,null,null,null,null,10,0,false);
            current.put(source(user,signup),signup);
            affected=affected==null||joined.isBefore(affected)?joined:affected;
        }
        if(current.size()>maxReplayInputs)throw new IllegalStateException("SCORE_REPLAY_LIMIT_EXCEEDED");
        var projected=ScoreCalculator.replay(new ArrayList<>(current.values()),checkpoint==null?0:checkpoint.score(),checkpoint==null?Tier.BRONZE:checkpoint.displayTier());
        metrics.summary("score.replay.inputs").record(current.size());
        final Instant replayFrom=affected;
        boolean replay=replayFrom!=null && old.stream().anyMatch(r->!r.input().effectiveAt().isBefore(replayFrom));
        long version=((Number)summary.get("version")).longValue()+1;
        // Restore the prefix by reversing the affected suffix in reverse logical order.
        final Instant from=affected;
        List<Row> replaced=from==null?List.of():old.stream().filter(r->!r.input().effectiveAt().isBefore(from)).toList();
        int undo=((Number)summary.get("total_score")).intValue();
        for(var r:replaced.reversed()) {
            undo-=r.applied();
            append(user,"REVERSAL",ScoreLedgerReason.REVERSAL,r.input(),processing,hash("REVERSAL",processing,r.id()),
                    -r.raw(),-r.limited(),-r.applied(),undo,TierBands.of(undo),r.display(),r.id(),null,new Stored(r.input(),null,null,null));
        }
        for(var v:projected.results()) {
            Row prior=originals.get(source(user,v.input()));
            if(from==null || v.input().effectiveAt().isBefore(from))continue;
            var c=v.input().cycle()==null?null:projected.cycles().get(v.input().cycle().cycleId());
            var payload=new Stored(v.input(),c==null||v.input().kind()!=ScoreInput.Kind.CLOSE?null:c.result.name(),
                    c==null||v.input().kind()!=ScoreInput.Kind.CLOSE?null:c.successStreak,c==null||v.input().kind()!=ScoreInput.Kind.CLOSE?null:c.failureStreak);
            String idem=prior==null?hash("ORIGINAL",source(user,v.input()),v.input().sourceVersion())
                    :hash("REPLAY",processing,source(user,v.input()),v.input().sourceVersion(),prior.id());
            append(user,"RESULT",v.reason(),v.input(),processing,idem,v.raw(),v.limited(),v.applied(),v.balance(),v.actual(),v.display(),null,prior==null?null:prior.id(),payload);
        }
        Map<UUID,Map<String,Object>> existingCycles=new HashMap<>();
        for(var row:jdbc.queryForList("SELECT * FROM cycle_score_states WHERE user_id=? ORDER BY challenge_id,cycle_id FOR UPDATE",bytes(user)))
            existingCycles.put(uuid((byte[])row.get("cycle_id")),row);
        for(var c:projected.cycles().values().stream().sorted(Comparator.comparing((ScoreCalculator.Cycle v)->v.spec.challengeId().toString()).thenComparing(v->v.spec.cycleId().toString())).toList())
            saveCycle(user,c,version,existingCycles.get(c.spec.cycleId()));
        jdbc.update("UPDATE user_score_summaries SET total_score=?,actual_tier=?,display_tier=?,version=?,updated_at=UTC_TIMESTAMP(3) WHERE user_id=?",
                projected.score(),projected.actual().name(),projected.display().name(),version,bytes(user));
        var result=new Completion(requestHash,inputs,projected.score(),projected.actual(),projected.display(),version,affected);
        boolean isCorrection=correction||replay;
        append(user,"COMMIT",isCorrection?ScoreLedgerReason.CORRECTION_COMMIT:ScoreLedgerReason.PROCESSING_COMMIT,null,processing,commitKey,
                0,0,0,projected.score(),projected.actual(),projected.display(),null,null,result);
        UUID event=UuidGenerator.generate();
        outbox.enqueue(ScoreStateOutboxHandler.TYPE,new ScoreStateOutboxHandler.Payload(event,user,version,projected.score(),projected.actual(),projected.display(),isCorrection),"score-state:"+user+":"+version);
        if(!isCorrection) {
            Tier before=Tier.valueOf(summary.get("display_tier").toString());
            var results=projected.results();
            for(int n=0;n<results.size();n++) {
                var value=results.get(n);
                if(affected==null || value.input().effectiveAt().isBefore(affected) || (n+1<results.size() && results.get(n+1).input().effectiveAt().equals(value.input().effectiveAt())))continue;
                var notice=TierNotice.of(before,value.display(),value.balance());
                before=value.display();
                if(!notice.isNone()) {
                    UUID noticeId=UuidGenerator.generate();
                    outbox.enqueue(ScoreTierNoticeHandler.TYPE,new ScoreTierNoticeHandler.Payload(noticeId,user,notice.kind().name(),notice.direction()),"score-tier:"+noticeId);
                }
            }
        }
        if(affected!=null)for(var c:projected.cycles().values())if(c.closedAt!=null && !c.closedAt.isBefore(affected)) {
            UUID sourceId=UuidGenerator.generate();
            outbox.enqueue(com.ruleup.ruleup_backend.room.service.RoomCycleResultHandler.TYPE,
                    new com.ruleup.ruleup_backend.room.service.RoomCycleResultHandler.Payload(sourceId,user,c.spec.challengeId(),c.spec.cycleNo(),
                            c.failureStreak,c.spec.startOn(),c.closedAt, c.spec.cycleId(),c.version,isCorrection,c.result.name(),c.successStreak,c.spec.joinedAt()),
                    "score-cycle:"+user+":"+c.spec.cycleId()+":"+c.version);
        }
        dispatcher.requestFlush();return result;
        } catch (RuntimeException e) {
            metrics.counter("score.processing.failures").increment();
            throw e;
        } finally {
            metrics.timer("score.processing.duration","correction",Boolean.toString(correction)).record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    public Optional<ScoreInput> original(UUID user, ScoreInput.Kind kind, String id) {
        return jdbc.query("SELECT t.payload_json FROM score_transactions t WHERE t.user_id=? AND t.source_event_key=? AND t.entry_kind='RESULT' AND NOT EXISTS (SELECT 1 FROM score_transactions r WHERE r.reversal_of=t.id)",
                (rs,n)->json.readValue(rs.getString(1),Stored.class).input(),bytes(user),hash(user,kind,id)).stream().findFirst();
    }
    public Optional<ScoreInput.CycleSpec> cycleSnapshot(UUID user,UUID challenge,int no) {
        return load(user).stream().map(Row::input).map(ScoreInput::cycle).filter(Objects::nonNull)
                .filter(c->c.challengeId().equals(challenge)&&c.cycleNo()==no).findFirst();
    }
    private List<Row> load(UUID user) {
        return jdbc.query("SELECT t.* FROM score_transactions t WHERE t.user_id=? AND t.entry_kind='RESULT' AND NOT EXISTS (SELECT 1 FROM score_transactions r WHERE r.reversal_of=t.id) ORDER BY t.effective_at,t.effective_order",
                (rs,n)->new Row(uuid(rs.getBytes("id")),json.readValue(rs.getString("payload_json"),Stored.class).input(),rs.getInt("raw_delta"),rs.getInt("limited_delta"),rs.getInt("applied_delta"),rs.getInt("balance_after"),Tier.valueOf(rs.getString("actual_tier_after")),Tier.valueOf(rs.getString("display_tier_after"))),bytes(user));
    }
    private void saveCycle(UUID user,ScoreCalculator.Cycle c,long version,Map<String,Object> previous) {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("tier_snapshot",c.tier.name());values.put("success_count",c.success);values.put("miss_count",c.miss);
        values.put("raw_cumulative",c.raw);values.put("limited_cumulative",c.limited);values.put("cycle_result",c.result==null?null:c.result.name());
        values.put("success_streak_after",c.closedAt==null?null:c.successStreak);values.put("failure_streak_after",c.closedAt==null?null:c.failureStreak);
        if(previous!=null && values.entrySet().stream().allMatch(e->Objects.equals(Objects.toString(e.getValue(),null),Objects.toString(previous.get(e.getKey()),null)))) {
            c.version=((Number)previous.get("version")).longValue();return;
        }
        c.version=version;

        jdbc.update("INSERT INTO cycle_score_states (user_id,challenge_id,cycle_id,cycle_start_on,cycle_end_on,membership_joined_at_snapshot,policy_version,tier_snapshot,target_count,success_weight,miss_weight,success_count,miss_count,raw_cumulative,limited_cumulative,cycle_result,success_streak_after,failure_streak_after,closed_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE tier_snapshot=VALUES(tier_snapshot),success_weight=VALUES(success_weight),miss_weight=VALUES(miss_weight),success_count=VALUES(success_count),miss_count=VALUES(miss_count),raw_cumulative=VALUES(raw_cumulative),limited_cumulative=VALUES(limited_cumulative),cycle_result=VALUES(cycle_result),success_streak_after=VALUES(success_streak_after),failure_streak_after=VALUES(failure_streak_after),closed_at=VALUES(closed_at),version=VALUES(version)",
                bytes(user),bytes(c.spec.challengeId()),bytes(c.spec.cycleId()),c.spec.startOn(),c.spec.endOn(),utc(c.spec.joinedAt()),c.spec.policyVersion(),c.tier.name(),c.spec.target(),TierPoints.weeklyGain(c.tier),TierPoints.weeklyPenalty(c.tier),c.success,c.miss,c.raw,c.limited,c.result==null?null:c.result.name(),c.closedAt==null?null:c.successStreak,c.closedAt==null?null:c.failureStreak,c.closedAt==null?null:utc(c.closedAt),version);
    }
    private void append(UUID user,String kind,ScoreLedgerReason reason,ScoreInput i,String processing,String idem,int raw,int limited,int applied,int balance,Tier actual,Tier display,UUID reversal,UUID replacement,Object payload) {
        jdbc.update("INSERT INTO score_transactions (id,user_id,entry_kind,reason,source_type,source_event_key,source_version,processing_key,idempotency_key,effective_at,effective_order,policy_version,auth_type,challenge_id,cycle_id,incident_type,raw_delta,limited_delta,applied_delta,balance_after,actual_tier_after,display_tier_after,reversal_of,replacement_of,payload_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                bytes(UuidGenerator.generate()),bytes(user),kind,reason.name(),i==null?"PROCESSING":i.kind().name(),i==null?null:source(user,i),i==null?null:i.sourceVersion(),processing,idem,
                utc(i==null?Instant.now():i.effectiveAt()),i==null?new byte[]{127}:order(i),i==null?null:ScoreInput.POLICY,i==null?null:i.authType(),i==null?null:bytes(i.challengeId()),i==null||i.cycle()==null?null:bytes(i.cycle().cycleId()),i==null||i.incidentType()==null?null:i.incidentType().name(),
                raw,limited,applied,balance,actual.name(),display.name(),bytes(reversal),bytes(replacement),json.writeValueAsString(payload));
    }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
}
