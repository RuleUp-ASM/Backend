package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeArchiveService;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProfileSpecAlignmentIT extends ChallengeApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationDailyRepository dailies;
    @Autowired ChallengeArchiveService archive;
    @MockitoBean ContentModerationClient moderation;
    MockMvc mvc;
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbc; }
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        when(moderation.moderateNickname(anyString())).thenReturn(ModerationResult.APPROVED);
        when(moderation.moderateImage(anyString())).thenReturn(ModerationResult.APPROVED);
    }
    private String nickname() { return "수정"+UUID.randomUUID().toString().substring(0,6); }
    private MvcResult rename(Member me) throws Exception { return patchJsonAuth("/api/v1/users/me/profile",me.token(),Map.of("nickname",nickname())); }
    private MvcResult upload(Member me) throws Exception {
        return mvc.perform(multipart("/api/v1/users/me/profile-image")
                .file(new MockMultipartFile("image","avatar.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47}))
                .header("Authorization","Bearer "+me.token())).andReturn();
    }

    @Test void twoConcurrentNicknameChangesShareOneLockAndReportItsExpiry() throws Exception {
        Member me=member(uniq("profile-concurrent"));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->rename(me));var b=executor.submit(()->rename(me));
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().map(r->r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200,409);
            MvcResult denied=results.stream().filter(r->r.getResponse().getStatus()==409).findFirst().orElseThrow();
            assertThat((String)read(denied,"$.error.profileLockedUntil")).isNotBlank();
        }
    }

    @Test void rejectionOnlyExemptsTheRejectedFieldAndDoesNotExtendTheLock() throws Exception {
        Member me=member(uniq("profile-repair"));
        jdbc.update("UPDATE users SET profile_changed_at=DATE_SUB(NOW(),INTERVAL 1 DAY),profile_image_key='https://cdn/rejected.png',profile_image_status='REJECTED' WHERE id=?",bytes(me.id()));
        var before=jdbc.queryForObject("SELECT profile_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,bytes(me.id()));
        assertThat(rename(me).getResponse().getStatus()).isEqualTo(409);
        assertThat(patchJsonAuth("/api/v1/users/me/profile",me.token(),Map.of("removeProfileImage",true)).getResponse().getStatus()).isEqualTo(200);
        jdbc.update("UPDATE users SET nickname_status='REJECTED',profile_image_key='https://cdn/approved.png',profile_image_status='APPROVED' WHERE id=?",bytes(me.id()));
        assertThat(patchJsonAuth("/api/v1/users/me/profile",me.token(),Map.of("nickname",nickname(),"removeProfileImage",true)).getResponse().getStatus()).isEqualTo(409);
        assertThat(rename(me).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT profile_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,bytes(me.id()))).isEqualTo(before);
    }

    @Test void firstImageIsFreeThenImageFirstSaveAllowsOneNicknameEdit() throws Exception {
        Member me=member(uniq("profile-image-lock"));
        assertThat(upload(me).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT profile_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,bytes(me.id()))).isNull();
        assertThat(upload(me).getResponse().getStatus()).isEqualTo(200);
        var before=jdbc.queryForObject("SELECT profile_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,bytes(me.id()));
        assertThat(upload(me).getResponse().getStatus()).isEqualTo(409);
        assertThat(rename(me).getResponse().getStatus()).isEqualTo(200);
        assertThat(rename(me).getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/profile/image").header("Authorization","Bearer "+me.token())).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT profile_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,bytes(me.id()))).isEqualTo(before);
    }

    @Test void calendarAndStatsReadImmediateAndCorrectedJudgementsBeforeCollection() throws Exception {
        Member me=member(uniq("profile-source"));UUID id=insertChallenge(me.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,me.id(),"OWNER");
        var bytes=ByteBuffer.wrap(jdbc.queryForObject("SELECT id FROM challenge_members WHERE challenge_id=?",byte[].class,bytes(id)));
        UUID membership=new UUID(bytes.getLong(),bytes.getLong());
        LocalDate yesterday=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        var pending=VerificationDaily.open(membership,id,me.id(),yesterday);pending.applyWindow(null);
        UUID verification=dailies.save(pending).getId();
        String date=yesterday.toString();
        var detail=getAuth("/api/v1/me/calendar/"+date,me.token());
        assertThat((String)read(detail,"$.data.items[0].status")).isEqualTo("FAIL_EXPECTED");
        assertThat((Boolean)read(detail,"$.data.items[0].appeal.eligible")).isTrue();
        assertThat((String)read(getAuth("/api/v1/me/calendar?month="+date.substring(0,7),me.token()),"$.data.days[0].status")).isEqualTo("FAIL_EXPECTED");
        jdbc.update("INSERT INTO RoutineOutcome(id,userId,challengeId,challengeMemberId,category,targetDate,status,confirmedAt) VALUES(?,?,?,?,?,?, 'FAILED',NOW())",bytes(UUID.randomUUID()),bytes(me.id()),bytes(id),bytes(membership),"EXERCISE",yesterday);
        jdbc.update("UPDATE VerificationDaily SET status='SUCCESS',verifiedAt=NOW(),verifiedVia='APPEAL',failureReason=NULL WHERE id=?",bytes(verification));
        var stats=getAuth("/api/v1/me/stats",me.token());
        assertThat(((Number)read(stats,"$.data.totalSuccessCount")).intValue()).isEqualTo(1);
        assertThat(((Number)read(stats,"$.data.successRate")).doubleValue()).isEqualTo(1);
        assertThat(((Number)read(stats,"$.data.streak.current")).intValue()).isEqualTo(1);
        assertThat((String)read(getAuth("/api/v1/me/calendar/"+date,me.token()),"$.data.items[0].status")).isEqualTo("DONE");
    }

    @Test void completionCountsSurviveArchiveAndUnlimitedRoomsStayInProgress() throws Exception {
        Member me=member(uniq("profile-count")), viewer=member(uniq("profile-view"));
        UUID done=insertChallenge(me.id(),"EXERCISE","COMPLETED","GROUP");insertActiveMembership(done,me.id(),"OWNER");
        jdbc.update("UPDATE challenge_members SET target_days=10,success_days=8,fail_days=2,progress_rate=80 WHERE challenge_id=?",bytes(done));
        UUID infinite=insertChallenge(me.id(),"EXERCISE","ACTIVE","GROUP");insertActiveMembership(infinite,me.id(),"OWNER");
        jdbc.update("UPDATE challenges SET end_date=NULL WHERE id=?",bytes(infinite));
        jdbc.update("UPDATE challenge_members SET progress_rate=100 WHERE challenge_id=?",bytes(infinite));
        for(int pass=0;pass<2;pass++) {
            assertThat(((Number)read(getAuth("/api/v1/me/stats",me.token()),"$.data.completedCount")).intValue()).isEqualTo(1);
            assertThat(((Number)read(getAuth("/api/v1/users/"+me.id()+"/profile",viewer.token()),"$.data.completedChallengeCount")).intValue()).isEqualTo(1);
            var home=getAuth("/api/v1/me/home",me.token());
            assertThat(((Number)read(home,"$.data.counts.completed")).intValue()).isEqualTo(1);
            assertThat(((Number)read(home,"$.data.counts.inProgress")).intValue()).isEqualTo(1);
            if(pass==0) assertThat(archive.deleteIfEligible(done)).isTrue();
        }
    }
    @Test void permissionWarningsIgnoreOldMembershipAndRestartOnNewGap() throws Exception {
        Member me=member(uniq("profile-permission"));UUID id=insertChallenge(me.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,me.id(),"OWNER");
        var service=wac.getBean(com.ruleup.ruleup_backend.verification.service.PermissionWaitService.class);
        var event=new com.ruleup.ruleup_backend.common.event.PermissionGapDetected(me.id(),id,"HEALTH",LocalDate.now(ZoneId.of("Asia/Seoul")),Instant.now());
        service.detected(event);
        var home=getAuth("/api/v1/me/home",me.token());
        assertThat(((Number)read(home,"$.data.permissionWarnings[0].remainingCycles")).intValue()).isEqualTo(2);
        byte[] source=jdbc.queryForObject("SELECT source_event_id FROM verification_permission_waits WHERE challenge_id=?",byte[].class,bytes(id));
        jdbc.update("UPDATE verification_permission_waits SET first_observed_at=DATE_SUB(NOW(),INTERVAL 28 DAY) WHERE challenge_id=?",bytes(id));
        assertThat((List<?>)read(getAuth("/api/v1/me/home",me.token()),"$.data.permissionWarnings")).isEmpty();
        service.detected(event);
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM verification_permission_waits WHERE challenge_id=?",byte[].class,bytes(id))).isNotEqualTo(source);
        assertThat(((Number)read(getAuth("/api/v1/me/home",me.token()),"$.data.permissionWarnings[0].remainingCycles")).intValue()).isEqualTo(2);
        assertThat(getAuth("/api/v1/me/invitation",me.token()).getResponse().getStatus()).isEqualTo(404);
    }

    /**
     * 앱 시계가 DB 시계보다 조금 뒤처져도 「이전 멤버십」으로 오해하지 않는다.
     *
     * <p>{@code first_observed_at} 은 앱이, {@code joined_at} 은 DB가 찍는다. 둘이 밀리초 단위로
     * 어긋나면 방금 만든 대기가 가입보다 먼저 찍히는데, 엄격히 비교하면 그 순간 대기가 사라져
     * 권한 경고가 뜨지 않고 강퇴 예약도 취소된다. 실제로 49ms 차이로 그렇게 됐었다.
     *
     * <p>진짜 경계는 재입장 대기 7일 뒤라 며칠씩 벌어져 있으므로, 초 단위 오차는 흡수해도 된다.
     * 그래서 오차 안쪽은 같은 멤버십, 바깥쪽은 이전 멤버십으로 갈린다.
     */
    @Test void permissionWarningSurvivesClockSkewButNotAPreviousMembership() throws Exception {
        Member me=member(uniq("profile-skew"));UUID id=insertChallenge(me.id(),"EXERCISE","ACTIVE","GROUP");
        insertActiveMembership(id,me.id(),"OWNER");
        var service=wac.getBean(com.ruleup.ruleup_backend.verification.service.PermissionWaitService.class);
        var event=new com.ruleup.ruleup_backend.common.event.PermissionGapDetected(
                me.id(),id,"HEALTH",LocalDate.now(ZoneId.of("Asia/Seoul")),Instant.now());
        service.detected(event);
        byte[] source=jdbc.queryForObject("SELECT source_event_id FROM verification_permission_waits WHERE challenge_id=?",byte[].class,bytes(id));

        skewFirstObservedBehindJoin(id,1);
        service.detected(event);
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM verification_permission_waits WHERE challenge_id=?",java.sql.Timestamp.class,bytes(id)))
                .as("시계 오차 안쪽이면 같은 멤버십의 대기라 해소되지 않는다").isNull();
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM verification_permission_waits WHERE challenge_id=?",byte[].class,bytes(id)))
                .as("대기가 이어지므로 원본 사건도 그대로다").isEqualTo(source);
        assertThat((List<?>)read(getAuth("/api/v1/me/home",me.token()),"$.data.permissionWarnings"))
                .as("경고도 계속 보인다").hasSize(1);

        skewFirstObservedBehindJoin(id,3600);
        assertThat((List<?>)read(getAuth("/api/v1/me/home",me.token()),"$.data.permissionWarnings"))
                .as("오차로 설명되지 않게 벌어지면 이전 멤버십의 대기다").isEmpty();
    }

    /** 대기를 가입보다 {@code seconds} 초 앞서게 돌린다 — 앱 시계가 그만큼 뒤처진 상황. */
    private void skewFirstObservedBehindJoin(UUID challengeId, int seconds) {
        jdbc.update("UPDATE verification_permission_waits w SET first_observed_at=DATE_SUB(" +
                "(SELECT MAX(joined_at) FROM challenge_join_events e WHERE e.challenge_id=w.challenge_id AND e.user_id=w.user_id)," +
                " INTERVAL ? SECOND) WHERE w.challenge_id=?", seconds, bytes(challengeId));
    }
}
