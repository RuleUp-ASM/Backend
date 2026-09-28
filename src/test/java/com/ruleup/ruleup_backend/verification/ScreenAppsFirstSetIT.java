package com.ruleup.ruleup_backend.verification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PUT my-screen-apps 가 첫 설정 경로로도 쓰인다(QA SETUP-05) — 앱은 셋업 화면에서도 이 API 로 저장한다.
 * 적용 중인 세트가 없는데 대기 세트로 넘기면 저장 직후 조회가 SCREENTIME_NOT_CONFIGURED, setup 은
 * PENDING_SETUP 그대로이고, 같은 앱으로 다시 저장하면 한도만 소진돼 429 가 났다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ScreenAppsFirstSetIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private UUID pendingSetupMember(UUID challengeId, UUID userId) {
        UUID memberId = UUID.randomUUID();
        jdbc().update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status, schedule_type, " +
                        "target_days, setup_status) VALUES (?, ?, ?, 'OWNER', 'ACTIVE', 'FIXED_DAYS', 14, 'PENDING_SETUP')",
                bytes(memberId), bytes(challengeId), bytes(userId));
        return memberId;
    }

    private MvcResult putApps(String token, UUID challengeId, String... packages) throws Exception {
        List<Map<String, String>> apps = java.util.Arrays.stream(packages)
                .map(p -> Map.of("packageName", p, "appName", p)).toList();
        return mvc.perform(put("/api/v1/challenges/" + challengeId + "/my-screen-apps")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("apps", apps)))).andReturn();
    }

    @Test
    @DisplayName("적용 중인 세트가 없으면 PUT 이 첫 설정이다 — 즉시 조회되고 셋업이 READY, 한도는 쓰지 않는다")
    void firstPutAppliesImmediately() throws Exception {
        Member me = member(uniq("apps-first"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        pendingSetupMember(challenge, me.id());

        assertThat(putApps(me.token(), challenge, "com.android.chrome").getResponse().getStatus()).isEqualTo(200);

        MvcResult apps = getAuth("/api/v1/challenges/" + challenge + "/my-screen-apps", me.token());
        assertThat(apps.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) read(apps, "$.data.apps[0].packageName")).isEqualTo("com.android.chrome");
        assertThat((Object) read(apps, "$.data.pending")).isNull();
        assertThat((Boolean) read(apps, "$.data.changeAvailable")).as("첫 설정은 월 1회를 쓰지 않는다").isTrue();

        MvcResult setup = getAuth("/api/v1/challenges/" + challenge + "/setup", me.token());
        assertThat((String) read(setup, "$.data.setupStatus")).isEqualTo("READY");
    }

    @Test
    @DisplayName("수정 전 첫 저장이 대기 세트로만 들어간 계정도 적용일 뒤 같은 앱을 다시 저장하면 READY 가 된다")
    void legacyPendingFirstSaveRecovers() throws Exception {
        Member me = member(uniq("apps-legacy"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        UUID memberId = pendingSetupMember(challenge, me.id());
        // 수정 전 동작이 남긴 상태: 현재 세트 없음, 대기 세트는 적용일이 지났고 한도는 이번 달에 소진됐다.
        jdbc().update("UPDATE challenge_members SET pending_screen_apps = ?, " +
                        "pending_screen_apps_effective_date = DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 1 DAY), " +
                        "screen_apps_changed_at = UTC_TIMESTAMP(6) WHERE id = ?",
                screenApps("com.android.chrome"), bytes(memberId));

        assertThat(putApps(me.token(), challenge, "com.android.chrome").getResponse().getStatus()).isEqualTo(200);
        MvcResult setup = getAuth("/api/v1/challenges/" + challenge + "/setup", me.token());
        assertThat((String) read(setup, "$.data.setupStatus")).isEqualTo("READY");
    }

    @Test
    @DisplayName("같은 앱으로 다시 저장하면 한도를 쓰지 않는다 — 다른 앱으로의 변경만 월 1회를 쓴다")
    void sameAppsResaveDoesNotConsumeLimit() throws Exception {
        Member me = member(uniq("apps-same"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        pendingSetupMember(challenge, me.id());

        putApps(me.token(), challenge, "com.android.chrome");
        assertThat(putApps(me.token(), challenge, "com.android.chrome").getResponse().getStatus())
                .as("QA SETUP-05 — 같은 값 재저장이 429 SETTING_CHANGE_LIMIT").isEqualTo(200);

        // 실제 변경은 익일 적용 + 한도 소진, 그 뒤 같은 대기 세트를 다시 보내도 한도와 무관하다.
        assertThat(putApps(me.token(), challenge, "com.ridi.books").getResponse().getStatus()).isEqualTo(200);
        assertThat(putApps(me.token(), challenge, "com.ridi.books").getResponse().getStatus()).isEqualTo(200);
        MvcResult limited = putApps(me.token(), challenge, "com.kakao.talk");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    }
}
