package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
import com.ruleup.ruleup_backend.verification.service.VerificationIntegrityCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationIntegrityRepairIT extends VerificationApiSupport {
    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationDailyRepository repository;
    @Autowired VerificationFinalizeService finalizer;
    @Autowired VerificationIntegrityCheck integrity;
    @Autowired TransactionTemplate transactions;
    @Autowired com.ruleup.ruleup_backend.score.service.ScoreSyncService scoreSync;
    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        jdbc().execute("DROP PROCEDURE IF EXISTS repair_verification_daily");
        String script = Files.readString(Path.of("tools/maintenance/repair-verification-daily.sql"));
        jdbc().execute(script.substring(script.indexOf("CREATE PROCEDURE"), script.indexOf("END//") + 3));
    }
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private VerificationDaily open() throws Exception {
        Member me = member(uniq("repair"));
        LocalDate date = LocalDate.now(KST).minusDays(2);
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30}");
        jdbc().update("UPDATE challenges SET start_date=? WHERE id=?", date, bytes(challenge));
        UUID member = insertReadyMember(challenge, me.id(), null, null);
        jdbc().update("UPDATE challenge_members SET joined_at=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 4 DAY) WHERE id=?", bytes(member));
        return repository.saveAndFlush(VerificationDaily.open(member, challenge, me.id(), date));
    }

    @Test void retryDoesNotCorruptDeadlineOrIntegrityCounts() throws Exception {
        VerificationDaily daily = open();
        var before = integrity.check();
        transactions.executeWithoutResult(tx -> repository.deferFinalize(daily.getId(), Instant.now().plusSeconds(600)));
        var deferred = repository.findById(daily.getId()).orElseThrow();
        assertThat(deferred.getFinalizeAfter()).isEqualTo(VerificationDeadlines.finalizeAfter(daily.getTargetDate()));
        assertThat(deferred.getVersion()).isEqualTo(daily.getVersion() + 1);
        assertThat(integrity.check()).isEqualTo(before);
        finalizer.finalizeDue();
        assertThat(repository.findById(daily.getId()).orElseThrow().isPending()).isTrue();
    }

    @Test void persistenceRejectsMalformedWrites() throws Exception {
        VerificationDaily daily = open();
        ReflectionTestUtils.setField(daily, "finalizeAfter", Instant.now());
        VerificationDaily invalidDeadline = daily;
        assertThatThrownBy(() -> repository.saveAndFlush(invalidDeadline)).hasRootCauseInstanceOf(IllegalStateException.class);
        daily = repository.findById(daily.getId()).orElseThrow();
        ReflectionTestUtils.setField(daily, "status", com.ruleup.ruleup_backend.common.verification.VerificationStatus.FAILED);
        VerificationDaily invalid = daily;
        assertThatThrownBy(() -> repository.saveAndFlush(invalid)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(daily.getId()).orElseThrow().isPending()).isTrue();
    }

    @Test void archivedFailureReplaysThroughScoreAndWatcherOutboxes() throws Exception {
        VerificationDaily daily = open();
        jdbc().update("UPDATE VerificationDaily SET status='FAILED', verifiedVia='AUTO', "
                + "finalizeAfter=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 3 DAY), appealClosesAt=UTC_TIMESTAMP() WHERE id=?",
                bytes(daily.getId()));
        scoreSync.syncConfirmedJudgements();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM score_transactions WHERE user_id=? AND source_type='DAILY' AND entry_kind='RESULT'",
                Integer.class, bytes(daily.getUserId()))).isZero();
        assertThatThrownBy(() -> jdbc().update("CALL repair_verification_daily(?, ?)", daily.getId().toString(), daily.getVersion() + 1))
                .hasMessageContaining("version changed");
        jdbc().update("CALL repair_verification_daily(?, ?)", daily.getId().toString(), daily.getVersion());
        assertThat(repository.findById(daily.getId()).orElseThrow().isPending()).isTrue();
        assertThat(jdbc().queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.status')) FROM verification_integrity_repairs WHERE verification_id=?",
                String.class, bytes(daily.getId()))).isEqualTo("FAILED");

        finalizer.finalizeDue();
        var repaired = repository.findById(daily.getId()).orElseThrow();
        assertThat(repaired.getStatus().name()).isEqualTo("FAILED");
        assertThat(repaired.hasInvalidFailure()).isFalse();
        assertThat(repaired.getVerifiedVia()).isNull();
        assertThat(repaired.getVersion()).isGreaterThan(daily.getVersion());
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE dedup_key=?", Integer.class,
                "score-input:" + daily.getId() + ":" + repaired.getScoreVersion())).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM outbox_messages WHERE dedup_key=?", Integer.class,
                "ROUTINE_FAILURE_CONFIRMED:" + daily.getId())).isEqualTo(1);
        scoreSync.syncConfirmedJudgements();
        assertThat(jdbc().queryForObject("SELECT MAX(source_version) FROM score_transactions WHERE user_id=? AND source_event_key=? AND entry_kind='RESULT'",
                Long.class, bytes(daily.getUserId()), com.ruleup.ruleup_backend.score.ScoreKeys.hash(
                        daily.getUserId(), "DAILY", daily.getId()))).isEqualTo(repaired.getScoreVersion());
        assertThatThrownBy(() -> jdbc().update("CALL repair_verification_daily(?, ?)", daily.getId().toString(), repaired.getVersion()))
                .hasMessageContaining("does not need repair");
    }

    @Test void alreadyDeliveredResultIsNotSilentlyReplayed() throws Exception {
        VerificationDaily daily = open();
        jdbc().update("UPDATE VerificationDaily SET status='FAILED' WHERE id=?", bytes(daily.getId()));
        jdbc().update("INSERT INTO notifications (id,user_id,type,title,body,created_at,dedup_key) VALUES (?,?, 'VERIFICATION_RESULT','결과','결과',UTC_TIMESTAMP(),?)",
                bytes(UUID.randomUUID()), bytes(daily.getUserId()),
                "VERIFICATION_RESULT:" + daily.getUserId() + ":" + daily.getId());
        assertThatThrownBy(() -> jdbc().update("CALL repair_verification_daily(?, ?)", daily.getId().toString(), daily.getVersion()))
                .hasMessageContaining("Existing result delivery");
        assertThat(repository.findById(daily.getId()).orElseThrow().getVersion()).isEqualTo(daily.getVersion());
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM verification_integrity_repairs WHERE verification_id=?",
                Integer.class, bytes(daily.getId()))).isZero();
    }
}
