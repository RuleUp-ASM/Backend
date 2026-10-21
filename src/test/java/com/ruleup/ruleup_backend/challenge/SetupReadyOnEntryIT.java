package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 고를 것이 없는 자동 방은 들어오는 즉시 평가 대상(READY)이다.
 *
 * <p>READY 로 가는 길이 POST /setup 뿐이던 때, 앱이 기상 방에서 「설정할 것 없음」을 보고 그 호출을
 * 건너뛰자 방장이 영영 PENDING_SETUP 으로 남았다 — sync 가 평가를 건너뛰어 성공도 실패도 뜨지 않았다
 * (2026-10-21 prod). 장소·앱을 골라야 하는 방은 예전처럼 셋업을 기다린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SetupReadyOnEntryIT extends ChallengeApiSupport {

    private static final long WAKE_TEMPLATE = 9711L;
    private static final long GYM_TEMPLATE = 9712L;
    private static boolean fixtures;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (!fixtures) {
            insertAutoTemplate(WAKE_TEMPLATE, "아침 7시 기상", "일찍 일어나는 습관", "WAKE_SLEEP",
                    "{\"target_time\":{\"default\":\"07:00\",\"unit\":\"hh:mm\"}}",
                    "WAKE", "[\"android.permission.health.READ_STEPS\"]");
            insertAutoTemplate(GYM_TEMPLATE, "헬스장 가기", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"duration_min\":{\"default\":60,\"unit\":\"min\",\"min\":10,\"max\":480}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\",\"ACCESS_BACKGROUND_LOCATION\"]");
            fixtures = true;
        }
    }

    @Test
    @DisplayName("기상 방: 방장은 만드는 즉시, 참여자는 들어오는 즉시 READY")
    void wakeRoomIsReadyWithoutSetup() throws Exception {
        Member owner = member(uniq("wake-owner"));
        UUID challengeId = createFromTemplate(owner, WAKE_TEMPLATE, "target_time", "07:00");

        assertThat(setupStatus(challengeId, owner.id())).isEqualTo("READY");

        Member joiner = member(uniq("wake-joiner"));
        MvcResult join = postJsonAuth("/api/v1/challenges/" + challengeId + "/members", joiner.token(), Map.of());
        assertThat(join.getResponse().getStatus()).isEqualTo(200);
        assertThat(setupStatus(challengeId, joiner.id())).isEqualTo("READY");
    }

    @Test
    @DisplayName("장소를 골라야 하는 방은 셋업 전까지 PENDING_SETUP 그대로")
    void gpsRoomStillWaitsForSetup() throws Exception {
        Member owner = member(uniq("gym-owner"));
        UUID challengeId = createFromTemplate(owner, GYM_TEMPLATE, "duration_min", "60");

        assertThat(setupStatus(challengeId, owner.id())).isEqualTo("PENDING_SETUP");
    }

    @Test
    @DisplayName("V18: 이미 갇힌 멤버 중 고를 것 없는 자동 방만 READY 로 올린다")
    void migrationLiftsOnlyStuckMembersOfRoomsWithoutBinding() throws Exception {
        Member user = member(uniq("v18"));
        UUID wakeAuto = insertTemplateChallenge(user.id(), WAKE_TEMPLATE, "AUTO");
        UUID wakeManual = insertTemplateChallenge(user.id(), WAKE_TEMPLATE, "MANUAL");
        UUID gymAuto = insertTemplateChallenge(user.id(), GYM_TEMPLATE, "AUTO");
        for (UUID id : List.of(wakeAuto, wakeManual, gymAuto)) {
            jdbcTemplate.update("INSERT INTO challenge_members (id, challenge_id, user_id, role, status, setup_status) " +
                    "VALUES (?, ?, ?, 'OWNER', 'ACTIVE', 'PENDING_SETUP')", bytes(UUID.randomUUID()), bytes(id), bytes(user.id()));
        }

        String sql = new String(new ClassPathResource("db/migration/V18__ready_members_without_binding.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbcTemplate.update(sql.lines().filter(l -> !l.startsWith("--")).collect(Collectors.joining("\n")));

        assertThat(setupStatus(wakeAuto, user.id())).isEqualTo("READY");
        assertThat(setupStatus(wakeManual, user.id())).as("직접 인증으로 바꾼 방은 셋업과 무관하다").isEqualTo("PENDING_SETUP");
        assertThat(setupStatus(gymAuto, user.id())).as("장소를 골라야 하는 방").isEqualTo("PENDING_SETUP");
    }

    private UUID insertTemplateChallenge(UUID ownerId, long templateId, String selectedMethod) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO challenges " +
                        "(id, owner_id, title, ai_title, description, category, mode, capacity, repeat_days, " +
                        " duration_days, start_date, end_date, template_id, verification_config, params, " +
                        " penalty_config, reward_config, anonymity, status, moderation_status, ai_assisted, participant_count) " +
                        "VALUES (?, ?, '방', '방', '설명', 'WAKE_SLEEP', 'GROUP', 30, " +
                        " '[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\",\"SAT\",\"SUN\"]', 14, " +
                        " DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), " +
                        " DATE_ADD(DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')), INTERVAL 14 DAY), ?, ?, '{}', " +
                        " '{\"mannerDeduction\":1.0}', '{\"mannerGain\":1.0}', 'REAL', 'ACTIVE', 'NONE', 1, 1)",
                bytes(id), bytes(ownerId), templateId,
                "{\"selectedMethod\":\"" + selectedMethod + "\",\"verificationType\":\"PHONE\",\"signalSource\":\"HC_RECORD\"," +
                        "\"wearableReq\":\"NONE\",\"requiredPermissions\":[]}");
        return id;
    }

    /** 경로 A(by-template) 초안을 그대로 그룹 방으로 만든다. */
    private UUID createFromTemplate(Member owner, long templateId, String paramKey, String paramValue) throws Exception {
        MvcResult draft = postJsonAuth("/api/v1/challenges/recommendation/by-template", owner.token(),
                Map.of("templateId", templateId));
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draft, "$.data.draftId"));
        body.put("title", (String) read(draft, "$.data.draft.title"));
        body.put("description", (String) read(draft, "$.data.draft.description"));
        body.put("category", (String) read(draft, "$.data.draft.category"));
        body.put("mode", "GROUP");
        body.put("visibility", "PUBLIC");
        body.put("capacity", 30);
        body.put("minTier", (String) read(draft, "$.data.draft.minTier"));
        body.put("period", Map.of(
                "start", (String) read(draft, "$.data.draft.period.start"),
                "end", (String) read(draft, "$.data.draft.period.end")));
        body.put("weeklyCount", (Integer) read(draft, "$.data.draft.weeklyCount"));
        body.put("params", List.of(Map.of("key", paramKey, "value", paramValue)));
        body.put("verification", Map.of(
                "type", (String) read(draft, "$.data.draft.verification.type"),
                "method", (String) read(draft, "$.data.draft.verification.method")));
        body.put("penalties", Map.of("watcher", false));

        MvcResult created = mvc.perform(post("/api/v1/challenges")
                .header("Authorization", "Bearer " + owner.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(OM.writeValueAsString(body))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(read(created, "$.data.challengeId"));
    }

    private String setupStatus(UUID challengeId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT setup_status FROM challenge_members WHERE challenge_id = ? AND user_id = ?",
                String.class, bytes(challengeId), bytes(userId));
    }
}
