package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.service.ScoreProcessor;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.score.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest @Import(TestcontainersConfiguration.class)
class ScoreReplayIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate db;
    @Autowired ScoreProcessor processor;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.ruleup.ruleup_backend.common.outbox.OutboxService outbox;
    private MockMvc mvc;
    private static final LocalDate START=LocalDate.of(2027,1,4);
    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return db; }

    static ScoreInput.CycleSpec cycle(UUID challenge,int no) {
        var start=START.plusWeeks(no-1);
        return new ScoreInput.CycleSpec(challenge,UUID.nameUUIDFromBytes((challenge+":"+no).getBytes()),no,start,start.plusDays(6),
                START.minusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),START,1,List.of(start),ScoreInput.POLICY);
    }
    static ScoreInput daily(ScoreInput.CycleSpec c,String result,int version) {
        return new ScoreInput(ScoreInput.Kind.DAILY,c.cycleId()+":day",version,c.startsAt(),"AUTO",c,c.startOn(),result,null,c.challengeId(),0,0,false);
    }
    static ScoreInput close(ScoreInput.CycleSpec c) {
        return new ScoreInput(ScoreInput.Kind.CLOSE,c.cycleId().toString(),1,c.closesAt(),"AUTO",c,null,null,null,c.challengeId(),0,0,false);
    }
    private ScoreProcessor.Completion run(UUID user,String key,List<ScoreInput> inputs,boolean correction) {
        return processor.process(user,key,processor.inputHash(inputs),inputs,correction);
    }
    private int count(UUID user,String predicate) {
        return db.queryForObject("SELECT COUNT(*) FROM score_transactions WHERE user_id=? AND "+predicate,Integer.class,bytes(user));
    }
    private int score(UUID user) { return db.queryForObject("SELECT total_score FROM user_score_summaries WHERE user_id=?",Integer.class,bytes(user)); }

    @Test void retriesReturnStoredCompletionAndRejectChangedInput() throws Exception {
        UUID user=member(uniq("replay-key")).id();var c=cycle(UUID.randomUUID(),1);
        var input=List.of(daily(c,"SUCCESS",1));
        var completed=run(user,"first",input,false);
        run(user,"close",List.of(close(c)),false);
        long version=db.queryForObject("SELECT version FROM user_score_summaries WHERE user_id=?",Long.class,bytes(user));
        assertThat(run(user,"first",input,false)).isEqualTo(completed);
        assertThat(db.queryForObject("SELECT version FROM user_score_summaries WHERE user_id=?",Long.class,bytes(user))).isEqualTo(version);
        assertThatThrownBy(()->run(user,"first",List.of(daily(c,"FAILED",2)),true)).hasMessage("SCORE_PROCESSING_KEY_CONFLICT");
        run(user,"same-original-new-envelope",input,false);
        assertThat(count(user,"source_type='DAILY' AND entry_kind='RESULT'")).isEqualTo(1);
        assertThat(count(user,"reason='SIGNUP'")).isEqualTo(1);
        assertThat(run(user,"zero-correction",input,true).score()).isEqualTo(completed.score());
        assertThat(count(user,"reason='CORRECTION_COMMIT' AND applied_delta=0")).isEqualTo(1);
    }

    @Test void correctionReplaysLaterTierSnapshotsAndPreservesIncidentFactAndOldRows() throws Exception {
        UUID user=member(uniq("replay-chain")).id();var first=cycle(UUID.randomUUID(),1);
        List<ScoreInput> inputs=new ArrayList<>();inputs.add(daily(first,"SUCCESS",1));inputs.add(close(first));
        for(int n=0;n<8;n++)inputs.add(daily(cycle(UUID.randomUUID(),1),"SUCCESS",1));
        var later=cycle(first.challengeId(),2);inputs.add(daily(later,"SUCCESS",1));inputs.add(close(later));
        var incident=new ScoreInput(ScoreInput.Kind.INCIDENT,"leave-fact",1,later.closesAt().plusMillis(1),"AUTO",null,null,null,
                IncidentType.VOLUNTARY_LEAVE,first.challengeId(),-15,0,false);
        inputs.add(incident);
        assertThat(run(user,"original",inputs,false).score()).isEqualTo(94);
        var originals=db.queryForList("SELECT HEX(id) id,payload_json,applied_delta FROM score_transactions WHERE user_id=? ORDER BY id",bytes(user));
        assertThat(run(user,"appeal",List.of(daily(first,"FAILED",2)),true).score()).isEqualTo(81);
        assertThat(db.queryForObject("SELECT tier_snapshot FROM cycle_score_states WHERE user_id=? AND cycle_id=?",String.class,bytes(user),bytes(later.cycleId()))).isEqualTo("BRONZE");
        assertThat(db.queryForObject("SELECT success_streak_after FROM cycle_score_states WHERE user_id=? AND cycle_id=?",Integer.class,bytes(user),bytes(later.cycleId()))).isEqualTo(1);
        assertThat(processor.original(user,ScoreInput.Kind.INCIDENT,"leave-fact")).contains(incident);
        assertThat(count(user,"entry_kind='REVERSAL'")).isEqualTo(inputs.size());
        for(var row:originals)assertThat(db.queryForMap("SELECT HEX(id) id,payload_json,applied_delta FROM score_transactions WHERE id=UNHEX(?)",row.get("id"))).isEqualTo(row);
        assertThat(count(user,"reason='CORRECTION_COMMIT' AND applied_delta=0")).isEqualTo(1);
        assertThat(processor.verifyUser(user)).isEmpty();
        db.update("UPDATE user_score_summaries SET total_score=82 WHERE user_id=?",bytes(user));
        assertThat(processor.verifyUser(user)).containsExactly("SUMMARY");
    }

    @Test void failedOutboxRollsBackCommitLedgerSummaryAndCyclesBeforeRetry() throws Exception {
        UUID user=member(uniq("replay-rollback")).id();var c=cycle(UUID.randomUUID(),1);
        int before=count(user,"1=1");
        long version=db.queryForObject("SELECT version FROM user_score_summaries WHERE user_id=?",Long.class,bytes(user));
        org.mockito.Mockito.doThrow(new IllegalStateException("injected outbox failure")).when(outbox).enqueue(
                org.mockito.ArgumentMatchers.eq("ACCOUNT_TIER_STATE_CHANGED"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("score-state:"+user+":"+(version+1)));
        try {
            assertThatThrownBy(()->run(user,"atomic",List.of(daily(c,"SUCCESS",1)),false)).isInstanceOf(RuntimeException.class);
            assertThat(score(user)).isEqualTo(10);
            assertThat(count(user,"1=1")).isEqualTo(before);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM cycle_score_states WHERE user_id=?",Integer.class,bytes(user))).isZero();
        } finally { org.mockito.Mockito.reset(outbox); }
        assertThat(run(user,"atomic",List.of(daily(c,"SUCCESS",1)),false).score()).isEqualTo(20);
    }

    @Test void reverseArrivalAndConcurrentDuplicateConverge() throws Exception {
        UUID user=member(uniq("replay-arrival")).id();var c=cycle(UUID.randomUUID(),1);
        var c2=cycle(UUID.randomUUID(),1);var a=daily(c,"SUCCESS",1);var b=daily(c2,"SUCCESS",1);
        run(user,"b",List.of(b),false);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<ScoreProcessor.Completion>>();
            for(int n=0;n<4;n++)futures.add(executor.submit(()->run(user,"a",List.of(a),false)));
            for(var future:futures)assertThat(future.get().score()).isEqualTo(30);
        }
        assertThat(score(user)).isEqualTo(30);
        assertThat(count(user,"entry_kind='RESULT' AND NOT EXISTS (SELECT 1 FROM score_transactions r WHERE r.reversal_of=score_transactions.id)")).isEqualTo(3);
    }

    /**
     * 요약이 원장과 어긋난 계정도 되감기가 막히지 않는다(QA TIER-15 B1).
     *
     * <p>되감기 행의 잔액을 요약에서 빼 가며 계산하면, 요약이 원장보다 작을 때 음수가 되어
     * ck_score_balance 위반으로 트랜잭션이 통째로 롤백됐다 — 그 계정의 되감기가 필요한 모든 점수
     * 입력이 영구히 막혔다. 원장 행이 기록한 잔액으로 되감고, 요약은 재생값으로 다시 맞춘다.
     */
    @Test void rewindUsesLedgerBalanceEvenWhenSummaryDrifted() throws Exception {
        UUID user=member(uniq("replay-drift")).id();
        var a=daily(cycle(UUID.randomUUID(),1),"SUCCESS",1);var b=daily(cycle(UUID.randomUUID(),1),"SUCCESS",1);
        assertThat(run(user,"b",List.of(b),false).score()).isEqualTo(20);
        db.update("UPDATE user_score_summaries SET total_score=0 WHERE user_id=?",bytes(user));   // 원장 20, 요약 0

        assertThat(run(user,"a",List.of(a),false).score()).isEqualTo(30);   // b 를 되감고 a·b 를 재적재

        assertThat(score(user)).isEqualTo(30);
        assertThat(db.queryForObject("SELECT balance_after FROM score_transactions WHERE user_id=? AND entry_kind='REVERSAL'",
                Integer.class,bytes(user))).isEqualTo(10);   // b 가 반영되기 직전 원장 잔액
        assertThat(processor.verifyUser(user)).isEmpty();
    }

    @Test void oneYearOfThreeChallengesRemainsReplayableWithinTransactionBudget() throws Exception {
        UUID user=member(uniq("replay-year")).id();
        List<ScoreInput> history=new ArrayList<>();
        for(int room=0;room<3;room++) {
            UUID challenge=UUID.randomUUID();
            for(int week=1;week<=52;week++) {
                var base=cycle(challenge,week);
                var dates=java.util.stream.IntStream.range(0,7).mapToObj(base.startOn()::plusDays).toList();
                var c=new ScoreInput.CycleSpec(challenge,base.cycleId(),week,base.startOn(),base.endOn(),base.joinedAt(),base.firstScorableOn(),4,dates,ScoreInput.POLICY);
                for(int day=0;day<7;day++) {
                    var date=dates.get(day);
                    history.add(new ScoreInput(ScoreInput.Kind.DAILY,c.cycleId()+":"+date,1,date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),"AUTO",c,date,day<4?"SUCCESS":"INVALID",null,challenge,0,0,false));
                }
                history.add(close(c));
            }
        }
        run(user,"year-history",history,false);
        assertThat(processor.verifyUser(user)).isEmpty();
        long[] times=new long[20];
        for(int n=0;n<times.length;n++) {
            var incident=new ScoreInput(ScoreInput.Kind.INCIDENT,"perf-leave-"+n,1,START.plusYears(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().plusSeconds(n),"AUTO",null,null,null,
                    IncidentType.VOLUNTARY_LEAVE,UUID.randomUUID(),-15,0,false);
            long started=System.nanoTime();run(user,"perf-"+n,List.of(incident),false);times[n]=System.nanoTime()-started;
        }
        Arrays.sort(times);
        System.out.printf("SCORE_REPLAY_BENCH history=%d requests=%d p95Millis=%.1f maxMillis=%.1f%n",history.size(),times.length,times[18]/1_000_000d,times[19]/1_000_000d);
        assertThat(processor.verifyUser(user)).isEmpty();
    }

    @Test void tierGateUsesCurrentDisplayAndPreservesCompletedMembershipWithoutAnotherPenalty() throws Exception {
        UUID user=member(uniq("tier-state-consumer")).id();
        UUID active=insertChallenge(user,"EXERCISE","ACTIVE","GROUP");insertActiveMembership(active,user,"OWNER");
        UUID completed=insertChallenge(user,"EXERCISE","COMPLETED","GROUP");insertActiveMembership(completed,user,"OWNER");
        db.update("UPDATE challenges SET min_tier='SILVER' WHERE id IN (?,?)",bytes(active),bytes(completed));
        var consumer=wac.getBean(com.ruleup.ruleup_backend.challenge.lifecycle.ScoreStateOutboxHandler.class);
        var old=new com.ruleup.ruleup_backend.challenge.lifecycle.ScoreStateOutboxHandler.Payload(UUID.randomUUID(),user,1,79,Tier.BRONZE,Tier.BRONZE,false);
        String payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(old);
        db.update("UPDATE user_score_summaries SET total_score=110,actual_tier='SILVER',display_tier='SILVER' WHERE user_id=?",bytes(user));
        consumer.handle(payload);
        assertThat(db.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=?",String.class,bytes(active))).isEqualTo("ACTIVE");
        db.update("UPDATE user_score_summaries SET total_score=79,actual_tier='BRONZE',display_tier='BRONZE' WHERE user_id=?",bytes(user));
        consumer.handle(payload);
        var left=db.queryForMap("SELECT leave_reason,rejoin_available_at FROM challenge_members WHERE challenge_id=?",bytes(active));
        assertThat(left).containsEntry("leave_reason","TIER_GATE").containsEntry("rejoin_available_at",null);
        assertThat(db.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=?",String.class,bytes(completed))).isEqualTo("ACTIVE");
        assertThat(score(user)).isEqualTo(79);
        assertThat(count(user,"source_type='INCIDENT'")).isZero();
    }
}
