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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 「AT + 활성 기기 검증」 <b>엄격 모드</b>(백엔드 4-4).
 *
 * <p>기기를 밝히지 않은 요청을 통과시키면 활성 기기 검증은 사실상 없는 것과 같다 — 교체 전 기기의
 * 백로그도 {@code deviceId} 만 빼면 그대로 판정에 들어간다. 그래서 엄격 모드에서는 받지 않는다.
 *
 * <p>기본값이 꺼짐인 이유는 계약에 기기가 없던 시절의 앱을 한 번에 인증 불가로 만들지 않기
 * 위해서다. 켜는 시점은 `verification.sync.device_id_missing` 이 0 으로 떨어졌을 때다.
 */
@SpringBootTest(properties = "app.verification.require-active-device=true")
@Import(TestcontainersConfiguration.class)
class VerificationStrictDeviceIT extends VerificationApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @Test
    @DisplayName("[P1] 엄격 모드에서는 기기를 밝히지 않은 sync 를 받지 않는다")
    void syncWithoutDeviceIdIsRejected() throws Exception {
        Member me = member(uniq("strict-device"));

        int status = postJsonAuth("/api/v1/verifications/sync", me.token(),
                syncBody(List.of(usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0)))))
                .getResponse().getStatus();

        assertThat(status)
                .as("통과시키면 deviceId 를 빼는 것만으로 활성 기기 검증을 우회할 수 있다")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("[P1] 기기를 밝히면 엄격 모드에서도 정상 처리된다")
    void syncWithDeviceIdPasses() throws Exception {
        Member me = member(uniq("strict-device-ok"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
        jdbc().update("UPDATE users SET device_id = ? WHERE id = ?", "device-A", bytes(me.id()));

        Map<String, Object> body = syncBody(List.of(
                usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0))));
        body.put("deviceId", "device-A");

        assertThat(postJsonAuth("/api/v1/verifications/sync", me.token(), body)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(todayStatusOf(memberId)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("[P1] 계정에 활성 기기가 없으면 엄격 모드에서 신호를 판정에 쓰지 않는다")
    void signalsAreNotUsedWhenTheAccountHasNoActiveDevice() throws Exception {
        Member me = member(uniq("strict-device-unknown"));
        UUID challenge = insertAutoChallenge(me.id(), "SCREEN_TIME_MIN", "USAGE", "{\"duration_min\":30}");
        UUID memberId = insertReadyMember(challenge, me.id(), null, screenApps("com.ridi.books"));
        // 기기를 한 번도 등록하지 않은 계정. 대조할 대상이 없다.
        jdbc().update("UPDATE users SET device_id = NULL WHERE id = ?", bytes(me.id()));

        Map<String, Object> body = syncBody(List.of(
                usageSignal("com.ridi.books", todayAt(9, 0), todayAt(10, 0))));
        body.put("deviceId", "아무거나-적어도-통과하면-안-된다");

        // 요청 자체는 받는다 — 봉투가 기기를 밝혔으므로 형식은 갖췄다.
        assertThat(postJsonAuth("/api/v1/verifications/sync", me.token(), body)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(todayStatusOf(memberId))
                .as("모르면 통과시키는 구멍이 남아 있으면, 스위치를 켜도 기기를 등록한 적 없는 "
                        + "계정은 아무 값이나 적어 그대로 인증된다 — 켠 의미가 없다")
                .isNotEqualTo("SUCCESS");
    }
}
