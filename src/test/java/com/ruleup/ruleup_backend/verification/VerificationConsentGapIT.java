package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 개별 동의가 없는 사용자의 sync — <b>조용히 실패시키지 않는다</b>.
 *
 * <p>stg 에서 실제로 일어난 일이다(2026-09-21). 앱은 지오펜스 신호를 정상으로 올렸는데
 * {@code LOCATION_INFO} 동의 행이 없어 게이트가 적재 이전에 전량 폐기했고, 서버는 그 사실을
 * 응답 {@code consentRequired} 에만 실어 보냈다. 앱이 그 필드를 보지 않아 사용자는 아무것도
 * 모른 채 <b>나흘 내리 실패</b>했고, 실패 사유는 「체류 시간이 부족했어요」였다 — 서버가 신호를
 * 버려 놓고 사용자가 덜 머물렀다고 말한 것이다.
 *
 * <p>그래서 두 가지를 못 박는다. ① 동의 공백은 권한 공백과 같은 사건이라 고지한다.
 * ② 그 방식의 신호가 하나도 없으면 <b>평가하지 않는다</b> — 근거를 만들면 확정 배치가
 * 그 근거를 믿어 무신호를 목표 미달로 확정한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VerificationConsentGapIT extends VerificationApiSupport {

    private static final double GYM_LAT = 37.4979, GYM_LNG = 127.0276;
    private static final String VISIT_PARAMS = "{\"duration_min\":30,\"radius_m\":100}";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private void revokeLocationConsent(UUID userId) {
        jdbc().update("DELETE FROM user_agreement_states WHERE user_id = ? AND agreement_type = 'LOCATION_INFO'",
                bytes(userId));
    }

    private int countOf(String sql, UUID userId) {
        Integer count = jdbc().queryForObject(sql, Integer.class, bytes(userId));
        return (count != null) ? count : 0;
    }

    @Test
    @DisplayName("위치 동의가 없으면 신호를 버리되, 그 사실을 사용자에게 고지한다")
    void missingConsentIsNoticedInsteadOfSilentlyFailing() throws Exception {
        Member me = member(uniq("consent-gap"));
        revokeLocationConsent(me.id());
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
        UUID memberId = insertReadyMember(challengeId, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        var res = postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                geofenceSignal(memberId, "EXIT", todayAt(10, 0)))));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<String>) read(res, "$.data.consentRequired")).containsExactly("LOCATION_INFO");
        assertThat(countOf("SELECT COUNT(*) FROM verification_location_signals WHERE userId = ?", me.id()))
                .as("동의가 없으면 받아서 배제하는 것이 아니라 아예 적재하지 않는다")
                .isZero();
        assertThat(countOf("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND type = 'PERMISSION_REGRANT_REQUIRED'", me.id()))
                .as("고치라고 알려 주지 않으면 사용자는 매일 조용히 실패한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("그 방식의 신호가 하나도 없으면 평가하지 않는다 — 「체류 0분」을 근거로 만들지 않는다")
    void noSignalOfThatMethodLeavesNoEvidence() throws Exception {
        Member me = member(uniq("consent-evidence"));
        revokeLocationConsent(me.id());
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
        UUID memberId = insertReadyMember(challengeId, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0)))));

        assertThat(todayStatusOf(memberId)).isEqualTo("PENDING");
        assertThat(dwellMinutesOf(memberId))
                .as("잰 적이 없는데 0분이라고 적으면 확정 배치가 그것을 목표 미달로 읽는다")
                .isNull();
        assertThat(todayEvidenceOf(memberId))
                .as("남길 것은 「왜 못 쟀는가」 하나뿐이다")
                .contains("PERMISSION_MISSING");
    }

    @Test
    @DisplayName("동의가 있으면 전과 같다 — 신호가 적재되고 판정이 돈다")
    void consentedUserIsUnaffected() throws Exception {
        Member me = member(uniq("consent-ok"));
        UUID challengeId = insertAutoChallenge(me.id(), "GPS_PRESENCE", "GEOFENCE", VISIT_PARAMS);
        UUID memberId = insertReadyMember(challengeId, me.id(),
                anchor(GYM_LAT, GYM_LNG, 100, "헬스장"), null);

        postJsonAuth("/api/v1/verifications/sync", me.token(), syncBody(List.of(
                geofenceSignal(memberId, "ENTER", todayAt(9, 0)),
                geofenceSignal(memberId, "EXIT", todayAt(10, 0)))));

        assertThat(countOf("SELECT COUNT(*) FROM verification_location_signals WHERE userId = ?", me.id()))
                .isEqualTo(2);
        assertThat(dwellMinutesOf(memberId)).isEqualTo(60L);
        assertThat(countOf("SELECT COUNT(*) FROM notifications WHERE user_id = ? "
                + "AND type = 'PERMISSION_REGRANT_REQUIRED'", me.id())).isZero();
    }
}
