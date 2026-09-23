package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import com.ruleup.ruleup_backend.verification.service.VerificationIntegrityCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 불변식 점검은 어긋난 행만 센다 — 정상 경로로 만든 행은 세지 않는다. 공유 DB 라 증감으로 본다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationIntegrityCheckIT extends VerificationApiSupport {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationIntegrityCheck integrity;
    @Autowired org.springframework.web.context.WebApplicationContext wac;
    private MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private long count(String key) {
        return ((Number) integrity.check().get(key)).longValue();
    }

    private void insert(String status, LocalDate date, Timestamp deadline, Timestamp verifiedAt) throws Exception {
        Member me = member(uniq("integrity"));
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30}");
        UUID memberId = insertReadyMember(challengeId, me.id(), null, null);
        jdbcTemplate.update("INSERT INTO VerificationDaily (id, challengeMemberId, challengeId, userId, targetDate, status, " +
                        "finalizeAfter, appealClosesAt, verifiedAt, shareableAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                bytes(UUID.randomUUID()), bytes(memberId), bytes(challengeId), bytes(me.id()), date, status,
                deadline, deadline, verifiedAt, verifiedAt);
    }

    @Test
    @DisplayName("확정 시각 없는 FAILED 와 자정이 아닌 기한만 센다")
    void countsOnlyBrokenRows() throws Exception {
        LocalDate date = LocalDate.now(KST).minusDays(5);
        Timestamp midnight = Timestamp.from(VerificationDeadlines.appealClosesAt(date));
        long failedBefore = count("failed_unconfirmed");
        long deadlineBefore = count("appeal_deadline_off");

        insert("FAILED", date, midnight, midnight);                                     // 정상
        assertThat(count("failed_unconfirmed")).isEqualTo(failedBefore);
        assertThat(count("appeal_deadline_off")).isEqualTo(deadlineBefore);

        insert("FAILED", date.minusDays(1), Timestamp.from(VerificationDeadlines.appealClosesAt(date.minusDays(1))), null);
        assertThat(count("failed_unconfirmed")).isEqualTo(failedBefore + 1);

        insert("PENDING", date.minusDays(2), Timestamp.from(midnight.toInstant().plusSeconds(3 * 3600 + 17)), null);
        assertThat(count("appeal_deadline_off")).isEqualTo(deadlineBefore + 1);
    }
}
