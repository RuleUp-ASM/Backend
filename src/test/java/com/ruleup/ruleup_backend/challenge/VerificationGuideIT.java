package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.guide.VerificationGuideService;
import com.ruleup.ruleup_backend.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 인증 방법 안내({@code verification.guide}) 계약.
 *
 * <ul>
 *   <li>생성 응답은 LLM 을 기다리지 않는다 — 커밋 뒤 비동기로 채워진다.</li>
 *   <li>채워지기 전 상세 조회는 null(앱이 「아직 입력중입니다.」를 그린다).</li>
 *   <li>LLM 이 판정 기준 값(걸음 수 등)을 바꾸거나 빼면 버리고 서버 기본 문구를 쓴다.</li>
 *   <li>방장이 목표값을 바꾸면 옛 안내를 비우고 새 조건으로 다시 만든다.</li>
 * </ul>
 */
@SpringBootTest(properties = "app.llm.fake=false")
@Import({TestcontainersConfiguration.class, VerificationGuideIT.StubLlmConfig.class})
class VerificationGuideIT extends ChallengeApiSupport {

    static final AtomicReference<String> LLM_RESPONSE = new AtomicReference<>(null);
    static final AtomicInteger LLM_CALLS = new AtomicInteger();

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        LlmClient stubLlm() {
            return new LlmClient() {
                @Override public boolean isConfigured() { return true; }
                @Override public String generateText(String prompt) { return null; }
                @Override public String generateStructured(String prompt, String schema) {
                    LLM_CALLS.incrementAndGet();
                    return LLM_RESPONSE.get();
                }
                @Override public String generateText(String prompt, byte[] image, String mimeType) { return null; }
            };
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationGuideService guideService;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static final long STEPS_TEMPLATE = 9701L;
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        LLM_RESPONSE.set(null);
        if (!fixtures) {
            insertAutoTemplate(STEPS_TEMPLATE, "하루 만 보 걷기", "하루 동안 걸은 걸음 수로 확인해요.", "EXERCISE",
                    "{\"steps\":{\"default\":10000,\"unit\":\"count\",\"min\":1000,\"max\":100000}}",
                    "HEALTH", "[\"android.permission.health.READ_STEPS\"]");
            fixtures = true;
        }
    }

    // ===== 헬퍼 =====

    private UUID createStepsChallenge(String token, String steps) throws Exception {
        MvcResult draft = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", STEPS_TEMPLATE));
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draft, "$.data.draftId"));
        body.put("title", (String) read(draft, "$.data.draft.title"));
        body.put("description", (String) read(draft, "$.data.draft.description"));
        body.put("category", (String) read(draft, "$.data.draft.category"));
        body.put("mode", (String) read(draft, "$.data.draft.mode"));
        body.put("visibility", (Object) read(draft, "$.data.draft.visibility"));
        body.put("rankingVisible", (Object) read(draft, "$.data.draft.rankingVisible"));
        body.put("capacity", (Integer) read(draft, "$.data.draft.capacity"));
        body.put("minTier", (String) read(draft, "$.data.draft.minTier"));
        body.put("period", Map.of(
                "start", (String) read(draft, "$.data.draft.period.start"),
                "end", (String) read(draft, "$.data.draft.period.end")));
        body.put("weeklyCount", 7);
        body.put("params", List.of(Map.of("key", "steps", "value", steps)));
        body.put("verification", Map.of("type", "AUTO", "method", "HEALTH"));
        body.put("penalties", Map.of("watcher", false));
        body.put("imageUrl", null);
        MvcResult res = mvc.perform(post("/api/v1/challenges")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(OM.writeValueAsString(body))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(read(res, "$.data.challengeId"));
    }

    private String storedGuide(UUID challengeId) {
        return jdbcTemplate.query("SELECT verification_guide FROM challenges WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, (Object) bytes(challengeId));
    }

    /** 커밋 뒤 비동기로 채워지므로 잠깐 기다린다. */
    private String awaitGuide(UUID challengeId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String g = storedGuide(challengeId);
            if (g != null) return g;
            Thread.sleep(50);
        }
        return null;
    }

    private Object detailGuide(String token, UUID challengeId) throws Exception {
        MvcResult res = getAuth("/api/v1/challenges/" + challengeId, token);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.verification.guide");
    }

    // ===== 계약 =====

    @Test
    @DisplayName("생성 뒤 LLM 문구가 채워지고, 상세 조회 verification.guide 로 내려간다")
    void llmGuideIsStoredAndServed() throws Exception {
        LLM_RESPONSE.set("{\"guide\":\"매일 12,000걸음 이상 걸으면 자동 인증됩니다.\"}");
        Member m = member(uniq("guide-llm"));

        UUID id = createStepsChallenge(m.token(), "12000");

        assertThat(awaitGuide(id)).isEqualTo("매일 12,000걸음 이상 걸으면 자동 인증됩니다.");
        assertThat(detailGuide(m.token(), id)).isEqualTo("매일 12,000걸음 이상 걸으면 자동 인증됩니다.");
    }

    @Test
    @DisplayName("LLM 이 걸음 수를 바꾸면 버리고 서버 기본 문구를 쓴다")
    void wrongNumberFallsBackToServerSentence() throws Exception {
        LLM_RESPONSE.set("{\"guide\":\"매일 10,000걸음 걸으면 자동 인증됩니다.\"}");   // 실제 목표는 8,000
        Member m = member(uniq("guide-wrong"));

        UUID id = createStepsChallenge(m.token(), "8000");

        assertThat(awaitGuide(id)).isEqualTo("매일 8,000걸음 이상 걸으면 자동 인증됩니다.");
    }

    @Test
    @DisplayName("LLM 이 응답하지 않아도 「입력중」에 머물지 않는다 — 기본 문구로 채운다")
    void llmDownStillFills() throws Exception {
        Member m = member(uniq("guide-down"));

        UUID id = createStepsChallenge(m.token(), "10000");

        assertThat(awaitGuide(id)).isEqualTo("매일 10,000걸음 이상 걸으면 자동 인증됩니다.");
    }

    @Test
    @DisplayName("아직 만들지 못했으면 상세 조회는 null 이다")
    void nullWhileGenerating() throws Exception {
        Member m = member(uniq("guide-null"));
        UUID id = createStepsChallenge(m.token(), "10000");
        awaitGuide(id);
        guideService.reset(id);   // 생성 직후(비동기 처리 전) 상태를 재현

        assertThat(detailGuide(m.token(), id)).isNull();
    }

    @Test
    @DisplayName("방장이 목표값을 바꾸면 옛 안내를 비우고 새 조건으로 다시 만든다")
    void patchParamsRegenerates() throws Exception {
        Member m = member(uniq("guide-patch"));
        UUID id = createStepsChallenge(m.token(), "10000");
        assertThat(awaitGuide(id)).contains("10,000걸음");
        Integer version = read(getAuth("/api/v1/challenges/" + id + "/settings", m.token()), "$.data.version");

        MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, m.token(), Map.of(
                "version", version,
                "params", List.of(Map.of("key", "steps", "value", "6000"))));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        String regenerated = null;
        for (int i = 0; i < 100 && (regenerated == null || regenerated.contains("10,000")); i++) {
            regenerated = storedGuide(id);
            Thread.sleep(50);
        }
        assertThat(regenerated).isEqualTo("매일 6,000걸음 이상 걸으면 자동 인증됩니다.");
    }

    @Test
    @DisplayName("이미 채워진 안내는 다시 만들지 않는다 — 보정 스캔이 겹쳐도 LLM 을 또 부르지 않는다")
    void alreadyFilledIsSkipped() throws Exception {
        Member m = member(uniq("guide-skip"));
        UUID id = createStepsChallenge(m.token(), "10000");
        awaitGuide(id);
        int before = LLM_CALLS.get();

        guideService.generate(id);

        assertThat(LLM_CALLS.get()).isEqualTo(before);
    }
}
