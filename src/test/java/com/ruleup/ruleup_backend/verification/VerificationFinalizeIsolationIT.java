package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.verification.domain.VerificationDaily;
import com.ruleup.ruleup_backend.verification.repository.VerificationDailyRepository;
import com.ruleup.ruleup_backend.verification.service.VerificationFinalizeService;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 확정 배치의 <b>실패 격리</b>가 실제로 동작하는지 (백엔드 4-3 「트랜잭션 경계」).
 *
 * <p>스펙이 "한 건의 판정 실패 때문에 전체 일 배치가 롤백되지 않도록" 하라고 적었다. 격리의 요점은
 * 「하나를 더 해보기」가 아니라 <b>문제 행을 비켜 가기</b>다 — 비켜 가지 못하면 그 한 건이 폴링
 * 커서 맨 앞에 영원히 앉아 <b>이후의 모든 확정을 막는다</b>. 하루치 판정이 통째로 멈추는 모양이다.
 *
 * <p>여기서는 챌린지 설정을 역직렬화할 수 없는 상태로 만들어 그 상황을 재현한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationFinalizeIsolationIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;

    /** 역직렬화가 실패하는 설정 — signalSource 가 계약에 없는 값이다. */
    private static final String BROKEN_CONFIG =
            "{\"selectedMethod\":\"AUTO\",\"verificationType\":\"PHONE\",\"signalSource\":\"NOT_A_SOURCE\","
                    + "\"wearableReq\":\"NONE\",\"requiredPermissions\":[]}";

    /** 원래대로 되돌릴 정상 설정. */
    private static final String SOUND_CONFIG =
            "{\"selectedMethod\":\"AUTO\",\"verificationType\":\"PHONE\",\"signalSource\":\"GEOFENCE\","
                    + "\"wearableReq\":\"NONE\",\"requiredPermissions\":[]}";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationFinalizeService finalizeService;
    @Autowired VerificationDailyRepository dailyRepository;

    private MockMvc mvc;

    /** 이 테스트가 일부러 망가뜨린 방들 — 반드시 되돌린다(아래 참조). */
    private final List<UUID> corrupted = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    /**
     * <b>망가뜨린 방은 반드시 되돌린다.</b> 통합 테스트는 스프링 컨텍스트와 DB 를 공유하므로,
     * 역직렬화 불가 상태로 남겨 두면 <b>모든 챌린지를 훑는 다른 배치 테스트가 통째로 터진다</b>
     * — 실제로 감시자 배치 테스트가 여기 걸렸다. 남의 테스트를 깨뜨리는 지뢰를 남기지 않는다.
     */
    @AfterEach
    void repairCorruptedChallenges() {
        for (UUID id : corrupted) {
            jdbc().update("UPDATE challenges SET verification_config = ? WHERE id = ?",
                    SOUND_CONFIG, bytes(id));
        }
        corrupted.clear();
    }

    /** 그 방의 설정을 읽을 수 없게 만든다 — 그 판정만 확정 중에 터진다. */
    private void corrupt(UUID challengeId) {
        jdbc().update("UPDATE challenges SET verification_config = ? WHERE id = ?",
                BROKEN_CONFIG, bytes(challengeId));
        corrupted.add(challengeId);
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static String visitParams() { return "{\"duration_min\":30,\"radius_m\":100}"; }

    /**
     * 확정 시각이 이미 지난 판정 행 하나를 연다.
     *
     * <p>시각 비교를 <b>전부 SQL 쪽에서</b> 한다. JVM 기본 시간대(KST)로 만든 {@code Timestamp} 를
     * UTC 세션에 그대로 쓰면 9시간이 어긋나 "한 시간 전"이 "여덟 시간 뒤"가 된다.
     */
    private UUID openDue(UUID memberId, UUID challengeId, UUID userId, LocalDate targetDate) {
        UUID id = dailyRepository.save(
                VerificationDaily.open(memberId, challengeId, userId, targetDate)).getId();
        jdbc().update("UPDATE VerificationDaily SET finalizeAfter = DATE_SUB(NOW(6), INTERVAL 1 HOUR) "
                + "WHERE id = ?", bytes(id));
        return id;
    }

    private String statusOf(UUID verificationId) {
        return jdbc().queryForObject("SELECT status FROM VerificationDaily WHERE id = ?",
                String.class, bytes(verificationId));
    }

    /** 폴링 커서가 미래로 밀렸는지 — 비교는 DB 가 한다. */
    private boolean deferred(UUID verificationId) {
        Boolean v = jdbc().queryForObject(
                "SELECT finalizeAfter > NOW(6) FROM VerificationDaily WHERE id = ?",
                Boolean.class, bytes(verificationId));
        return Boolean.TRUE.equals(v);
    }

    /** 밀린 시각 자체(문자열 비교용) — 두 번 돌아도 값이 그대로인지 보는 데 쓴다. */
    private String finalizeCursorOf(UUID verificationId) {
        return jdbc().queryForObject("SELECT finalizeAfter FROM VerificationDaily WHERE id = ?",
                String.class, bytes(verificationId));
    }

    @Test
    @DisplayName("[P1] 확정 시각이 앞당겨진 행은 폴러를 점유하지 않고, 정상 대상이 굶지 않는다")
    void anEarlyRowNeitherSpinsThePollerNorStarvesTheRest() throws Exception {
        Member me = member(uniq("finalize-early"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        startedDaysAgo(challenge, 10);
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        // ① 오늘 귀속인데 저장된 확정 시각만 과거로 당겨진 행. 확정 쪽은 귀속일에서 다시
        //    파생한 시각으로 거절하는데, 거절은 행을 바꾸지 않는다 — 폴링 조건이 이 행을
        //    계속 집으면 45초 예산을 통째로 여기에 쓰고 아래 ②가 영영 차례를 못 받는다.
        UUID early = openDue(memberId, challenge, me.id(), LocalDate.now(KST));
        // ② 정상적으로 확정돼야 하는 D-2 대상.
        UUID due = openDue(memberId, challenge, me.id(), LocalDate.now(KST).minusDays(2));

        long startedAt = System.nanoTime();
        finalizeService.finalizeDue();
        long elapsedSec = (System.nanoTime() - startedAt) / 1_000_000_000L;

        assertThat(statusOf(due))
                .as("앞의 한 건이 폴러를 붙잡으면 뒤에 밀린 정상 대상이 확정되지 못한다")
                .isNotEqualTo("PENDING");
        assertThat(statusOf(early))
                .as("귀속일 기준으로 아직 이르다 — 확정하면 이의 창이 열린 건을 실패로 굳힌다")
                .isEqualTo("PENDING");
        assertThat(elapsedSec)
                .as("같은 행을 다시 집으면 예산(45초)을 다 쓴다 — 애초에 집지 않아야 한다")
                .isLessThan(20L);
    }

    @Test
    @DisplayName("[P1] 판정 하나가 터져도 나머지는 확정되고, 터진 건만 뒤로 밀린다")
    void oneBrokenRowDoesNotBlockTheRest() throws Exception {
        Member me = member(uniq("finalize-isolate"));
        LocalDate targetDate = LocalDate.now(KST).minusDays(2);

        UUID brokenChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID brokenMember = insertReadyMember(brokenChallenge, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID broken = openDue(brokenMember, brokenChallenge, me.id(), targetDate);

        List<UUID> healthy = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
            UUID memberId = insertReadyMember(challenge, me.id(),
                    anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
            healthy.add(openDue(memberId, challenge, me.id(), targetDate));
        }

        corrupt(brokenChallenge);

        assertThatCode(() -> finalizeService.finalizeDue())
                .as("예외가 밖으로 튀면 스케줄러가 그 자리에서 멈춘다")
                .doesNotThrowAnyException();

        for (UUID id : healthy) {
            assertThat(statusOf(id))
                    .as("문제 행 하나가 뒤의 정상 건을 굶기면 하루치 판정이 통째로 멈춘다")
                    .isEqualTo("FAILED");
        }
        assertThat(statusOf(broken))
                .as("터진 건은 확정하지 않는다 — 근거 없는 실패를 남기지 않는다")
                .isEqualTo("PENDING");
        assertThat(deferred(broken))
                .as("뒤로 밀지 않으면 폴링 커서 맨 앞에 영원히 앉아 있는다")
                .isTrue();
    }

    @Test
    @DisplayName("[P1] 무신호 채우기도 깨진 멤버 하나에 통째로 롤백되지 않는다")
    void materializeIsolatesABrokenMember() throws Exception {
        Member me = member(uniq("materialize-isolate"));
        LocalDate targetDate = LocalDate.now(KST).minusDays(2);

        UUID brokenChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID brokenMember = insertReadyMember(brokenChallenge, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID healthyChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID healthyMember = insertReadyMember(healthyChallenge, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "도서관"), null);
        startedDaysAgo(brokenChallenge, 5);
        startedDaysAgo(healthyChallenge, 5);
        corrupt(brokenChallenge);

        finalizeService.materializeDueTargets();

        assertThat(rowExists(healthyMember, targetDate))
                .as("단일 트랜잭션이면 깨진 멤버 하나가 그날 채우기를 통째로 되돌리고, "
                        + "다음 날은 다른 날짜를 보므로 그 날짜는 영구히 비어 버린다")
                .isTrue();
        assertThat(rowExists(brokenMember, targetDate))
                .as("설정을 읽을 수 없는 멤버는 열지 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("[P1] 하루 걸러진 날짜도 다음 실행이 따라잡는다")
    void materializeCatchesUpOnSkippedDates() throws Exception {
        Member me = member(uniq("materialize-catchup"));
        UUID challenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID memberId = insertReadyMember(challenge, me.id(), anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        startedDaysAgo(challenge, 10);

        finalizeService.materializeDueTargets();

        // D-2 만 보면 그보다 오래된 날짜는 영영 열리지 않는다.
        assertThat(rowExists(memberId, LocalDate.now(KST).minusDays(4)))
                .as("확정 폴러는 행이 있어야 집는다 — 행 자체가 없는 날짜는 스스로 따라잡지 못한다")
                .isTrue();
    }

    /** 챌린지 시작일을 당겨 과거 날짜도 인증 대상이 되게 한다. */
    private void startedDaysAgo(UUID challengeId, int days) {
        jdbc().update("UPDATE challenges SET start_date = " +
                        " DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL ? DAY) " +
                        "WHERE id = ?", days, bytes(challengeId));
    }

    private boolean rowExists(UUID challengeMemberId, LocalDate date) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM VerificationDaily WHERE challengeMemberId = ? AND targetDate = ?",
                Integer.class, bytes(challengeMemberId), java.sql.Date.valueOf(date));
        return n != null && n > 0;
    }

    @Test
    @DisplayName("[P1] 격리된 건은 다음 차례가 와도 정상 건보다 앞서지 않는다")
    void deferredRowDoesNotJumpTheQueueAgain() throws Exception {
        Member me = member(uniq("finalize-defer"));
        LocalDate targetDate = LocalDate.now(KST).minusDays(2);

        UUID brokenChallenge = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", visitParams());
        UUID brokenMember = insertReadyMember(brokenChallenge, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);
        UUID broken = openDue(brokenMember, brokenChallenge, me.id(), targetDate);
        corrupt(brokenChallenge);

        finalizeService.finalizeDue();
        String firstDefer = finalizeCursorOf(broken);
        assertThat(deferred(broken)).isTrue();

        // 같은 tick 이 다시 돌아도 이미 밀린 건은 대상이 아니다.
        finalizeService.finalizeDue();

        assertThat(finalizeCursorOf(broken))
                .as("매 tick 마다 같은 행을 다시 집으면 격리가 아니라 무한 재시도다")
                .isEqualTo(firstDefer);
    }
}
