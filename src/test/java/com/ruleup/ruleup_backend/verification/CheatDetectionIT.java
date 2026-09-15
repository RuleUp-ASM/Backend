package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.common.outbox.OutboxDispatcher;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.CheatDetectionRepository;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.CheatDetectionService;
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
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 부정행위 검출 확정 → 집행 (인증 공통 5-7 · 5-3 {@code cheat_detections}).
 *
 * <p>인증 모듈은 <b>신호를 내는 데까지만</b> 책임지고 집행은 각 도메인이 맡는다. 그 경계가
 * 실제로 이어져 있는지를 본다 — 검출 1건이 강퇴·영구 차단·−50·필수(A) 통지를 <b>모두</b>
 * 만들어야 하고, 하나라도 빠지면 「강퇴는 됐는데 점수는 그대로」 같은 상태가 남는다.
 *
 * <p>누적 카운트가 없으므로 <b>확정 1건이 곧 제재</b>다. 그래서 같은 판정으로 두 번 확정되면
 * 안 된다 — 재시도는 오류가 아니지만 집행이 두 번 나가면 −100 이 된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CheatDetectionIT extends VerificationApiSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CheatDetectionService cheatDetectionService;
    @Autowired CheatDetectionRepository detectionRepository;
    @Autowired VerificationDailyRepository dailyRepository;
    @Autowired UserScoreSummaryRepository scoreRepository;
    @Autowired OutboxDispatcher outboxDispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    /**
     * 집행은 아웃박스를 거친다 — 확정 커밋과 <b>같은 스레드에서 끝나지 않는다.</b>
     *
     * <p>커밋 직후 즉시 경로는 전용 스레드에 신호만 보내고 돌아오므로(요청 스레드를 붙잡지
     * 않으려는 설계다), 여기서 흘리지 않고 바로 단언하면 아직 안 나간 상태를 보게 된다.
     * 예전에 통과하던 것은 그 경로가 마침 같은 스레드였기 때문이지 보장이 아니었다.
     * 유실을 막는 것은 어차피 스윕이고, 테스트는 그 스윕을 <b>지금</b> 한 번 돌린다.
     */
    private void drainOutbox() {
        outboxDispatcher.flush();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    private UUID openDaily(UUID memberId, UUID challengeId, UUID userId) {
        return dailyRepository.save(VerificationDaily.open(
                memberId, challengeId, userId, LocalDate.now(KST))).getId();
    }

    private long scoreOf(UUID userId) {
        return scoreRepository.findById(userId).map(s -> s.getTotalScore()).orElse(0L);
    }

    private boolean rejoinBanned(UUID challengeId, UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND user_id = ?"
                        + " AND rejoin_banned = 1", Integer.class, bytes(challengeId), bytes(userId));
        return n != null && n > 0;
    }

    /** 감점 원장 — 같은 검출이 두 번 반영되면 여기 두 줄이 된다. */
    private int cheatLedgerOf(UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM score_transactions WHERE user_id = ?"
                        + " AND incident_type = 'CHEAT_DETECTED'", Integer.class, bytes(userId));
        return n != null ? n : 0;
    }

    /**
     * 원장에 적힌 감점 총액. <b>잔액이 아니라 이 값을 본다</b> — 신규 유저는 잔액이 0 이라
     * 「0 에서 50 을 빼도 0」이 되어 집행 여부를 가릴 수 없다.
     */
    private int cheatDeltaOf(UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COALESCE(SUM(raw_delta), 0) FROM score_transactions WHERE user_id = ?"
                        + " AND incident_type = 'CHEAT_DETECTED'", Integer.class, bytes(userId));
        return n != null ? n : 0;
    }

    private int cheatNoticesOf(UUID userId) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'CHEAT_DETECTED'",
                Integer.class, bytes(userId));
        return n != null ? n : 0;
    }

    @Test
    @DisplayName("검출 1건이 기록·강퇴·영구 차단·감점·통지를 한 번에 만든다 — 하나라도 빠지면 안 된다")
    void oneDetectionExecutesEverything() throws Exception {
        Member me = member(uniq("cheat"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID verificationId = openDaily(memberId, challenge, me.id());
        long scoreBefore = scoreOf(me.id());

        cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "IMPOSSIBLE_TRAVEL", "observed", "120km/h"), Instant.now());
        drainOutbox();

        assertThat(detectionRepository.findByVerificationDailyId(verificationId))
                .as("무엇을 근거로 확정했는지 남는다").isPresent();
        assertThat(rejoinBanned(challenge, me.id()))
                .as("해당 챌린지 영구 차단 — 백오프가 아니다").isTrue();
        assertThat(cheatLedgerOf(me.id())).as("감점 원장 한 줄").isEqualTo(1);
        assertThat(cheatDeltaOf(me.id()))
                .as("−50 전액 — 사건성 감점은 사이클 ±20 한도를 거치지 않는다").isEqualTo(-50);
        assertThat(scoreOf(me.id()))
                .as("누적 점수는 0 아래로 내려가지 않는다").isEqualTo(Math.max(0, scoreBefore - 50));
        assertThat(cheatNoticesOf(me.id())).as("필수(A) 통지").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 판정으로 두 번 확정해도 집행은 한 번뿐이다 — 재시도는 오류가 아니지만 −100 이면 안 된다")
    void secondConfirmDoesNotExecuteAgain() throws Exception {
        Member me = member(uniq("cheat-idem"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID verificationId = openDaily(memberId, challenge, me.id());
        long scoreBefore = scoreOf(me.id());

        cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "REPEATED_MOCK"), Instant.now());
        cheatDetectionService.confirm(me.id(), challenge, verificationId,
                Map.of("rule", "REPEATED_MOCK"), Instant.now());
        drainOutbox();

        assertThat(detectionRepository.findByUserIdOrderByDetectedAtDesc(me.id()))
                .as("검출 기록도 하나뿐이다").hasSize(1);
        assertThat(cheatLedgerOf(me.id())).as("감점도 한 번뿐이다 — 두 줄이면 −100 이다").isEqualTo(1);
        assertThat(cheatDeltaOf(me.id())).as("총액도 −50 그대로다").isEqualTo(-50);
        assertThat(scoreOf(me.id())).isEqualTo(Math.max(0, scoreBefore - 50));
        assertThat(cheatNoticesOf(me.id())).as("같은 통지가 두 번 가지 않는다").isEqualTo(1);
    }
}
