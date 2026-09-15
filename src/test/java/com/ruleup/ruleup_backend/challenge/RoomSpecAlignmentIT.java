package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.domain.RejoinBackoff;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeArchiveService;
import com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService;
import com.ruleup.ruleup_backend.common.event.PermissionGapDetected;
import com.ruleup.ruleup_backend.room.service.*;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.PermissionWaitService;
import com.ruleup.ruleup_backend.verification.service.VerificationBatchCompletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomSpecAlignmentIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired AutomaticKickService kicks;
    @Autowired ChallengeArchiveService archive;
    @Autowired CrossRankingSnapshotService rankings;
    @Autowired ChallengeMemberService members;
    @Autowired PermissionWaitService permissions;
    @Autowired PermissionKickHandler permissionHandler;
    @Autowired VerificationBatchCompletion completion;
    @Autowired VerificationDailyRepository dailies;
    @Autowired TransactionTemplate transactions;
    MockMvc mvc;
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbc; }
    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    @Test void sourceEventIsIdempotentAndBackoffKeepsDoubling() throws Exception {
        Member owner=member(uniq("backoff-owner")), target=member(uniq("backoff-target"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,owner.id(),"OWNER");insertActiveMembership(id,target.id(),"MEMBER");
        for (int n=0;n<3;n++) {
            UUID event=UUID.randomUUID();
            assertThat(kicks.enforce(id,target.id(),AutomaticKickService.Reason.CONSECUTIVE_FAILURE,event,Instant.now(),Map.of("failureStreak",3))).isTrue();
            assertThat(kicks.enforce(id,target.id(),AutomaticKickService.Reason.CONSECUTIVE_FAILURE,event,Instant.now(),Map.of())).isFalse();
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(DAY,kicked_at,rejoin_available_at) FROM challenge_kicks WHERE source_event_id=?",Integer.class,bytes(event))).isEqualTo(7*(1<<n));
            // A later membership starts after the cooldown. Replaying its old source cannot end it.
            jdbc.update("UPDATE challenge_members SET status='ACTIVE',left_at=NULL,left_type=NULL,leave_reason=NULL,rejoin_available_at=NULL WHERE challenge_id=? AND user_id=?",bytes(id),bytes(target.id()));
            assertThat(kicks.enforce(id,target.id(),AutomaticKickService.Reason.CONSECUTIVE_FAILURE,event,Instant.now(),Map.of())).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=?",Integer.class,bytes(id))).isEqualTo(3);
        assertThat(RejoinBackoff.weeks(8)).isEqualTo(256);
    }

    @Test void permanentEvidenceSurvivesRoomArchive() throws Exception {
        Member owner=member(uniq("kick-archive"));UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        assertThat(kicks.enforce(id,owner.id(),AutomaticKickService.Reason.CHEAT_DETECTED,UUID.randomUUID(),null,Map.of("detector","test"))).isTrue();
        assertThat(archive.deleteIfEligible(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=? AND is_permanent=1",Integer.class,bytes(id))).isEqualTo(1);
        var response=getAuth("/api/v1/users/me/sanctions",owner.token());
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat((String)read(response,"$.data.auto[0].reasonCode")).isEqualTo("CHEAT_DETECTED");
    }

    @Test void leavingRemovesOnlyTheDepartingContributionFromTheDailySnapshot() throws Exception {
        Member owner=member(uniq("ranking-owner")), target=member(uniq("ranking-leaver"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,owner.id(),"OWNER");insertActiveMembership(id,target.id(),"MEMBER");
        jdbc.update("UPDATE challenge_members SET success_days=40,fail_days=10 WHERE challenge_id=?",bytes(id));
        rankings.refresh();
        jdbc.update("UPDATE challenge_members SET success_days=99 WHERE challenge_id=? AND user_id=?",bytes(id),bytes(owner.id()));
        members.leave(target.id(),id);
        var row=jdbc.queryForMap("SELECT member_count,success_count,total_count,rank_no FROM challenge_cross_ranking_snapshot WHERE challenge_id=?",bytes(id));
        assertThat(row.get("member_count")).isEqualTo(1);
        assertThat(row.get("success_count")).isEqualTo(40);
        assertThat(row.get("total_count")).isEqualTo(50);
        assertThat(row.get("rank_no")).isNotNull();
    }

    @Test void deferredJudgementPreventsCompletionMarker() throws Exception {
        Member owner=member(uniq("batch-marker"));UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        var b=ByteBuffer.wrap(jdbc.queryForObject("SELECT id FROM challenge_members WHERE challenge_id=?",byte[].class,bytes(id)));
        LocalDate day=LocalDate.of(1901,1,3);
        UUID verification=dailies.save(VerificationDaily.open(new UUID(b.getLong(),b.getLong()),id,owner.id(),day.minusDays(2))).getId();
        jdbc.update("UPDATE VerificationDaily SET finalizeAfter=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE id=?",bytes(verification));
        completion.materialized(day);completion.finishIfDrained(day);
        assertThat(jdbc.queryForObject("SELECT finalized_at FROM verification_batch_completions WHERE batch_on=?",java.sql.Timestamp.class,day)).isNull();
        jdbc.update("UPDATE VerificationDaily SET status='NOT_TARGET' WHERE id=?",bytes(verification));
        completion.finishIfDrained(day);
        assertThat(jdbc.queryForObject("SELECT finalized_at FROM verification_batch_completions WHERE batch_on=?",java.sql.Timestamp.class,day)).isNotNull();
    }

    @Test void crossRankingRefreshWaitsForMarkerAndRunsOnce() throws Exception {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("DELETE FROM verification_batch_completions WHERE batch_on=?",today);
        jdbc.update("UPDATE room_job_locks SET last_run_on=NULL WHERE job_name='CROSS_RANKING'");
        rankings.refreshAfterVerification();
        assertThat(jdbc.queryForObject("SELECT last_run_on FROM room_job_locks WHERE job_name='CROSS_RANKING'",java.sql.Date.class)).isNull();
        jdbc.update("INSERT INTO verification_batch_completions VALUES(?,NOW(),NOW())",today);
        rankings.refreshAfterVerification();
        assertThat(jdbc.queryForObject("SELECT last_run_on FROM room_job_locks WHERE job_name='CROSS_RANKING'",java.sql.Date.class).toLocalDate()).isEqualTo(today);
        var at=jdbc.queryForObject("SELECT MAX(snapshot_at) FROM challenge_cross_ranking_snapshot",java.sql.Timestamp.class);
        rankings.refreshAfterVerification();
        assertThat(jdbc.queryForObject("SELECT MAX(snapshot_at) FROM challenge_cross_ranking_snapshot",java.sql.Timestamp.class)).isEqualTo(at);
    }

    @Test void restoredPermissionCancelsDelayedKickAndOldEventsCannotKickRejoinedMembers() throws Exception {
        Member owner=member(uniq("permission-wait"));UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("UPDATE challenges SET start_date=? WHERE id=?",today.minusDays(28),bytes(id));
        Instant first=Instant.now().minus(Duration.ofDays(20));
        permissions.detected(new PermissionGapDetected(owner.id(),id,"HEALTH",today.minusDays(20),first));
        var source=ByteBuffer.wrap(jdbc.queryForObject("SELECT source_event_id FROM verification_permission_waits WHERE challenge_id=?",byte[].class,bytes(id)));
        UUID eventId=new UUID(source.getLong(),source.getLong());
        var payload=new PermissionKickHandler.Payload(id,owner.id(),"HEALTH",eventId,today.minusDays(14));
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload);
        // The event predates this membership even though its two-cycle wait elapsed.
        transactions.executeWithoutResult(tx -> permissionHandler.handle(json));
        assertThat(jdbc.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=?",String.class,bytes(id))).isEqualTo("ACTIVE");
        permissions.received(new PermissionWaitService.MeasurementReceived(id,owner.id(),"HEALTH",Instant.now()));
        transactions.executeWithoutResult(tx -> permissionHandler.handle(json));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=?",Integer.class,bytes(id))).isZero();
    }
    @Test void maturedPermissionWaitIsEnforcedOnlyOnce() throws Exception {
        Member owner=member(uniq("permission-due"));UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("UPDATE challenges SET start_date=? WHERE id=?",today.minusDays(28),bytes(id));
        jdbc.update("UPDATE challenge_join_events SET joined_at=DATE_SUB(NOW(),INTERVAL 28 DAY) WHERE challenge_id=?",bytes(id));
        permissions.detected(new PermissionGapDetected(owner.id(),id,"HEALTH",today.minusDays(20),Instant.now().minus(Duration.ofDays(20))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND type='PERMISSION_REGRANT_REQUIRED'",Integer.class,bytes(owner.id()))).isEqualTo(1);
        permissions.publishDue();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=? AND reason='PERMISSION_MISSING'",Integer.class,bytes(id))).isEqualTo(1));
        permissions.publishDue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=?",Integer.class,bytes(id))).isEqualTo(1);
    }

    @Test void consecutiveCycleSignalWarnsThenKicksAndCannotUndoMembership() throws Exception {
        Member owner=member(uniq("cycle-signal"));UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        LocalDate start=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(28);
        jdbc.update("UPDATE challenges SET start_date=? WHERE id=?",start,bytes(id));
        jdbc.update("UPDATE challenge_join_events SET joined_at=? WHERE challenge_id=?",java.sql.Timestamp.from(start.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),bytes(id));
        var handler=wac.getBean(RoomCycleResultHandler.class);
        for (int streak=2;streak<=3;streak++) {
            var event=new RoomCycleResultHandler.Payload(UUID.randomUUID(),owner.id(),id,streak,streak,start.plusDays(7L*(streak-1)),Instant.now());
            String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(event);
            transactions.executeWithoutResult(tx -> handler.handle(json));
            transactions.executeWithoutResult(tx -> handler.handle(json));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND type='CONSECUTIVE_FAILURE_WARNING'",Integer.class,bytes(owner.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM challenge_kicks WHERE challenge_id=?",Integer.class,bytes(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT leave_reason FROM challenge_members WHERE challenge_id=?",String.class,bytes(id))).isEqualTo("KICKED");
    }

}
