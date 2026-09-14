package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeArchiveService;
import com.ruleup.ruleup_backend.challenge.moderation.*;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.notification.NotificationPublisher;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeLifecycleSpecIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChallengeArchiveService archive;
    @Autowired ChallengeModerationStore store;
    @Autowired ChallengeNameBlocklist blocklist;
    @Autowired NotificationPublisher notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.ruleup.ruleup_backend.challenge.service.ChallengeMemberService members;
    MockMvc mvc;
    @Override protected MockMvc mvc(){return mvc;}
    @Override protected JdbcTemplate jdbc(){return jdbc;}
    @BeforeEach void setup(){mvc= MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();}

    @Test void modeAndCapacityAreAppliedTogetherAndInvalidVisibilityRollsBack() throws Exception {
        Member owner=member(uniq("normalize"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","UPCOMING","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        int version=jdbc.queryForObject("SELECT version FROM challenges WHERE id=?",Integer.class,bytes(id));
        var result=patchJsonAuth("/api/v1/challenges/"+id,owner.token(),
                Map.of("version",version,"mode","GROUP","capacity",7,"visibility","PRIVATE"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var row=jdbc.queryForMap("SELECT capacity,visibility,version FROM challenges WHERE id=?",bytes(id));
        assertThat(row.get("capacity")).isEqualTo(7);
        assertThat(row.get("visibility")).isEqualTo("PRIVATE");
        var invalid=patchJsonAuth("/api/v1/challenges/"+id,owner.token(),
                Map.of("version",row.get("version"),"mode","GROUP","capacity",8,"visibility","TYPO"));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT capacity FROM challenges WHERE id=?",Integer.class,bytes(id))).isEqualTo(7);
    }

    @Test void unlimitedDurationCanBeSavedAndRead() throws Exception {
        Member owner=member(uniq("noend"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","UPCOMING","SOLO");
        insertActiveMembership(id,owner.id(),"OWNER");
        Map<String,Object> period=new LinkedHashMap<>();
        period.put("start",LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString());period.put("end",null);
        int version=jdbc.queryForObject("SELECT version FROM challenges WHERE id=?",Integer.class,bytes(id));
        var result=patchJsonAuth("/api/v1/challenges/"+id,owner.token(),Map.of("version",version,"period",period));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        for(String path:List.of("/api/v1/challenges/"+id,"/api/v1/challenges/"+id+"/settings","/api/v1/challenges?filter=IN_PROGRESS")) {
            assertThat(getAuth(path,owner.token()).getResponse().getStatus()).as(path).isEqualTo(200);
        }
        assertThat(archive.deleteIfEligible(id)).isFalse();
    }

    @Test void deletionRechecksMembershipAndWaitsForPendingWork() throws Exception {
        Member owner=member(uniq("recheck"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,owner.id(),"OWNER");
        assertThat(archive.deleteIfEligible(id)).isFalse();
        jdbc.update("UPDATE challenge_members SET status='LEFT' WHERE challenge_id=?",bytes(id));
        jdbc.update("UPDATE challenges SET moderation_title='IN_REVIEW',moderation_enqueued_at=NOW(),moderation_pending_since=NOW() WHERE id=?",bytes(id));
        assertThat(archive.deleteIfEligible(id)).isFalse();
        jdbc.update("UPDATE challenges SET moderation_title='APPROVED' WHERE id=?",bytes(id));
        assertThat(archive.deleteIfEligible(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT close_reason FROM challenge_history WHERE challenge_id=?",String.class,bytes(id))).isEqualTo("EMPTY");
    }

    @Test void deletionPreservesSettingsTimesAndCompletedApiContracts() throws Exception {
        Member owner=member(uniq("snapshot"));Member left=member(uniq("snapshot-left"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","COMPLETED","GROUP");
        insertActiveMembership(id,owner.id(),"OWNER");insertActiveMembership(id,left.id(),"MEMBER");
        jdbc.update("UPDATE challenges SET description='보존할 설명',capacity=27 WHERE id=?",bytes(id));
        jdbc.update("UPDATE challenge_members SET success_days=8,fail_days=2,progress_rate=35 WHERE challenge_id=?",bytes(id));
        jdbc.update("UPDATE challenge_members SET status='LEFT',left_type='LEAVE',leave_reason='VOLUNTARY', " +
                "joined_at='2026-01-01 00:00:00',left_at='2026-01-09 00:00:00' WHERE challenge_id=? AND user_id=?",bytes(id),bytes(left.id()));
        byte[] memberId=jdbc.queryForObject("SELECT id FROM challenge_members WHERE challenge_id=? AND user_id=?",byte[].class,bytes(id),bytes(owner.id()));
        jdbc.update("INSERT INTO verification_setting_snapshots(id,challengeMemberId,kind,effectiveFrom,payload) VALUES(?,?,'ANCHORS','2026-01-01','[]')",bytes(UUID.randomUUID()),memberId);
        assertThat(archive.deleteIfEligible(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM verification_setting_snapshots WHERE challengeMemberId=?",Integer.class,memberId)).isEqualTo(1);
        var times=jdbc.queryForMap("SELECT joined_at,left_at FROM challenge_member_history WHERE challenge_id=? AND user_id=?",bytes(id),bytes(left.id()));
        assertThat(times.get("joined_at").toString()).startsWith("2026-01-01");
        assertThat(times.get("left_at").toString()).startsWith("2026-01-09");
        var detail=getAuth("/api/v1/challenges/"+id,owner.token());
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat((String)read(detail,"$.data.description")).isEqualTo("보존할 설명");
        assertThat((Integer)read(detail,"$.data.capacity")).isEqualTo(27);
        var ranking=getAuth("/api/v1/challenges/"+id+"/ranking",owner.token());
        assertThat(ranking.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number)read(ranking,"$.data.me.successRate")).doubleValue()).isEqualTo(0.8);
        var completed=getAuth("/api/v1/challenges?filter=COMPLETED",owner.token());
        assertThat((String)read(completed,"$.data.challenges[0].description")).isEqualTo("보존할 설명");
        assertThat(archive.deleteIfEligible(id)).isFalse();
    }

    @Test void staleTitleResultDoesNotDiscardUnchangedDescriptionOrHoldRoomLock() throws Exception {
        Member owner=member(uniq("mod-race"));
        UUID id=insertChallenge(owner.id(),"EXERCISE","UPCOMING","GROUP");insertActiveMembership(id,owner.id(),"OWNER");
        jdbc.update("UPDATE challenges SET title='old',description='same',moderation_title='IN_REVIEW',moderation_description='IN_REVIEW',moderation_pending_since=NOW() WHERE id=?",bytes(id));
        ContentModerationClient client=mock(ContentModerationClient.class);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(client.moderateChallengeText(anyString(),anyString())).thenAnswer(inv->{
            entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            return new ContentModerationClient.TextVerdicts(ModerationResult.REJECTED,ModerationResult.APPROVED);
        });
        var service=new ChallengeModerationService(store,client,blocklist,notifications,transactions);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(()->service.moderate(id));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var updated=executor.submit(()->jdbc.update("UPDATE challenges SET title='new',version=version+1 WHERE id=?",bytes(id)));
                assertThat(updated.get(3,TimeUnit.SECONDS)).isEqualTo(1);
            } finally {release.countDown();}
            future.get(5,TimeUnit.SECONDS);
        }
        var row=jdbc.queryForMap("SELECT moderation_title,moderation_description,moderation_pending_since FROM challenges WHERE id=?",bytes(id));
        assertThat(row.get("moderation_title")).isEqualTo("IN_REVIEW");
        assertThat(row.get("moderation_description")).isEqualTo("APPROVED");
        assertThat(row.get("moderation_pending_since")).isNotNull();
    }

    @Test void nullContentAndDuplicateResultsUseFieldGuard() throws Exception {
        Member owner=member(uniq("nullcas"));UUID id=insertChallenge(owner.id(),"EXERCISE","UPCOMING","SOLO");
        jdbc.update("UPDATE challenges SET description=NULL,moderation_description='IN_REVIEW' WHERE id=?",bytes(id));
        var snapshot=store.read(id);
        transactions.executeWithoutResult(tx->{
            assertThat(store.apply(snapshot,ChallengeModerationSnapshot.Target.DESCRIPTION,"APPROVED")).isTrue();
            assertThat(store.apply(snapshot,ChallengeModerationSnapshot.Target.DESCRIPTION,"REJECTED")).isFalse();
        });
    }
    @Test void leaveOutboxRollsBackWithMembershipAndManualRoomsEmitNoScoreEvent() throws Exception {
        Member owner = member(uniq("leave-outbox"));
        UUID id = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(id, owner.id(), "OWNER");
        jdbc.update("UPDATE challenges SET verification_config=JSON_SET(verification_config,'$.selectedMethod','AUTO') WHERE id=?", bytes(id));
        transactions.executeWithoutResult(tx -> {
            assertThat(members.leave(owner.id(), id).scoreDelta()).isNegative();
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE type='CHALLENGE_LEAVE_SCORE' " +
                    "AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.challengeId'))=?", Integer.class, id.toString())).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM challenge_members WHERE challenge_id=?", String.class, bytes(id))).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE type='CHALLENGE_LEAVE_SCORE' " +
                "AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.challengeId'))=?", Integer.class, id.toString())).isZero();
        jdbc.update("UPDATE challenges SET verification_config=JSON_SET(verification_config,'$.selectedMethod','MANUAL') WHERE id=?", bytes(id));
        assertThat(members.leave(owner.id(), id).scoreDelta()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE type='CHALLENGE_LEAVE_SCORE' " +
                "AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.challengeId'))=?", Integer.class, id.toString())).isZero();
    }

}
