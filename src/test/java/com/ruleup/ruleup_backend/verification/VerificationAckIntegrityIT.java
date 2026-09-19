package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.domain.VerificationDeadlines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 결과 확인(ack)은 판정 저장 가드에 막히지 않는다 — 복구 전의 이상 행(확정 시각 없는 FAILED)에서
 * 확인이 500 이면 결과 모달이 닫히지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationAckIntegrityIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    private MockMvc mvc;

    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }
    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("확정 시각 없는 FAILED 행도 결과 확인은 된다 — 판정 필드는 그대로, 두 번 불러도 첫 시각 유지")
    void ackOnBrokenFailedRow() throws Exception {
        Member me = member(uniq("ack-broken"));
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", "{\"duration_min\":30}");
        UUID memberId = insertReadyMember(challengeId, me.id(), null, null);
        LocalDate date = LocalDate.now(KST).minusDays(3);
        Timestamp deadline = Timestamp.from(VerificationDeadlines.finalizeAfter(date));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO VerificationDaily (id,challengeMemberId,challengeId,userId,targetDate,status," +
                "finalizeAfter,appealClosesAt) VALUES (?,?,?,?,?,'FAILED',?,?)",
                bytes(id), bytes(memberId), bytes(challengeId), bytes(me.id()), date, deadline, deadline);

        var first = postJsonAuth("/api/v1/verifications/" + id + "/ack", me.token(), Map.of());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Timestamp ackedAt = jdbcTemplate.queryForObject(
                "SELECT acknowledgedAt FROM VerificationDaily WHERE id=?", Timestamp.class, bytes(id));
        assertThat(ackedAt).isNotNull();

        assertThat(postJsonAuth("/api/v1/verifications/" + id + "/ack", me.token(), Map.of())
                .getResponse().getStatus()).isEqualTo(200);
        var row = jdbcTemplate.queryForMap("SELECT status, verifiedAt, acknowledgedAt FROM VerificationDaily WHERE id=?", bytes(id));
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("verifiedAt")).as("판정 필드는 복구 도구가 다룬다").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT acknowledgedAt FROM VerificationDaily WHERE id=?",
                Timestamp.class, bytes(id))).as("멱등 — 첫 확인 시각 유지").isEqualTo(ackedAt);
    }
}
