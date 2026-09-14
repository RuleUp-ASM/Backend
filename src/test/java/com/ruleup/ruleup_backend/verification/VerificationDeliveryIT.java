package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.service.CheatKickOutboxHandler;
import com.ruleup.ruleup_backend.score.CheatScoreOutboxHandler;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.CheatDetectionService;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
import com.ruleup.ruleup_backend.watcher.service.WatcherFailureOutboxHandler;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 판정 신호의 외부 발행이 <b>유실되지 않는지</b> (인증 공통 5-7 「발행은 아웃박스 패턴을 쓴다」).
 *
 * <p>예전에는 인메모리 이벤트로 내고 수신측이 실패하면 경고만 남겼다. 그러면 통지 한 번이
 * 실패하는 순간 재시도할 근거가 어디에도 없고, 부정행위는 더 나빴다 — 검출 기록이 이미 있는
 * 재호출은 이벤트를 다시 내지 않아 <b>최초 집행 실패가 영구 미집행으로 굳었다</b>.
 *
 * <p>여기서 보는 것은 「집행이 됐는가」가 아니라 <b>「발행 의사가 판정과 같은 커밋에 남았는가」</b>다.
 * 그 행이 있으면 수신측이 몇 번을 실패하든 스윕이 다시 집는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationDeliveryIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CheatDetectionService cheatDetectionService;
    @Autowired VerificationFinalizeService finalizeService;
    @Autowired VerificationDailyRepository dailyRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private int outboxCount(String type, UUID anchorId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE type = ? AND dedup_key = ?",
                Integer.class, type, type + ":" + anchorId);
        return (n != null) ? n : 0;
    }

    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) WHERE id = ?",
                days, bytes(challengeId));
    }

    @Test
    @DisplayName("[P1] 실패 확정은 감시자 통지를 아웃박스에 적는다 — 통지가 실패해도 다시 집힌다")
    void failureConfirmationLeavesAnOutboxRow() throws Exception {
        Member me = member(uniq("delivery-fail"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 5);

        finalizeService.materializeDueTargets();
        finalizeService.finalizeDue();

        LocalDate twoDaysAgo = LocalDate.now(KST).minusDays(2);
        UUID verificationId = dailyRepository
                .findByChallengeMemberIdAndTargetDate(memberId, twoDaysAgo).orElseThrow().getId();

        assertThat(outboxCount(WatcherFailureOutboxHandler.OUTBOX_TYPE, verificationId))
                .as("인메모리 이벤트로 내면 통지 한 번의 실패가 영구 미통지가 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[P1] 부정행위 집행은 강퇴·감점을 따로 적어 재시도가 서로를 끌고 가지 않는다")
    void cheatEnforcementIsEnqueuedPerReceiver() throws Exception {
        Member me = member(uniq("delivery-cheat"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID verificationId = dailyRepository.save(VerificationDaily.open(
                memberId, challenge, me.id(), LocalDate.now(KST))).getId();

        var detection = cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "IMPOSSIBLE_TRAVEL"), Instant.now());

        assertThat(outboxCount(CheatKickOutboxHandler.OUTBOX_TYPE, detection.getId())).isEqualTo(1);
        assertThat(outboxCount(CheatScoreOutboxHandler.OUTBOX_TYPE, detection.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[P1] 기록만 남고 적재가 빠진 검출은 재확정에서 복구된다")
    void reconfirmRecoversAMissingEnforcementRow() throws Exception {
        Member me = member(uniq("delivery-recover"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID verificationId = dailyRepository.save(VerificationDaily.open(
                memberId, challenge, me.id(), LocalDate.now(KST))).getId();

        var detection = cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "IMPOSSIBLE_TRAVEL"), Instant.now());
        // 예전 구조에서 벌어지던 상태를 만든다 — 기록은 있는데 집행 신호가 없다.
        jdbc().update("DELETE FROM outbox_messages WHERE dedup_key = ?",
                CheatScoreOutboxHandler.OUTBOX_TYPE + ":" + detection.getId());
        assertThat(outboxCount(CheatScoreOutboxHandler.OUTBOX_TYPE, detection.getId())).isZero();

        cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "IMPOSSIBLE_TRAVEL"), Instant.now());

        assertThat(outboxCount(CheatScoreOutboxHandler.OUTBOX_TYPE, detection.getId()))
                .as("재확정이 아무것도 하지 않으면 최초 실패가 영구 미집행으로 굳는다")
                .isEqualTo(1);
    }
}
