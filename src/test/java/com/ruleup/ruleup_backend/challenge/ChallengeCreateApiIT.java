package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 챌린지 최종 생성 계약 테스트 — API 명세 「챌린지 최종 생성」·「챌린지 이미지 업로드」 기준.
 *
 *  - Idempotency-Key 필수(DB 유니크): 누락 400 / 동일 키+동일 본문 → 기존 201 재응답(중복 생성 없음) /
 *    동일 키+다른 본문 → 409 IDEMPOTENCY_CONFLICT.
 *  - draftId 원본 대조(P0 — 심사 우회 차단): title·description 이 원본과 다르면 서버가 심사 대상 판정.
 *    ai_title·출처는 draft 행에서 서버가 확보(클라 전송 필드 폐기). 무효 400 DRAFT_NOT_FOUND, 만료 400 DRAFT_EXPIRED.
 *  - 서버 강제: penalties.score(자동=ON)/groupShare(그룹=ON) 클라 값 무시, category 불변,
 *    minTier ≤ 생성자 표시 티어, AUTO→MANUAL 만 허용.
 *  - 이미지: 업로드 API가 발급한 본인 소유 URL 만 허용(임의 외부 URL 400, 타인 객체 403).
 */
@SpringBootTest(properties = "app.llm.fake=false")
@Import({TestcontainersConfiguration.class, ChallengeCreateApiIT.StubLlmConfig.class})
class ChallengeCreateApiIT extends ChallengeApiSupport {

    static final AtomicReference<String> LLM_RESPONSE = new AtomicReference<>(null);

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        LlmClient stubLlm() {
            return new LlmClient() {
                @Override public boolean isConfigured() { return true; }
                @Override public String generateText(String prompt) { return LLM_RESPONSE.get(); }
                @Override public String generateStructured(String prompt, String schema) { return LLM_RESPONSE.get(); }
                @Override public String generateText(String prompt, byte[] image, String mimeType) { return null; }
            };
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static final long GYM_TEMPLATE = 9201L;
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        LLM_RESPONSE.set(null);
        if (!fixtures) {
            insertAutoTemplate(GYM_TEMPLATE, "주 3회 헬스장", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"weekly_count\":{\"default\":3,\"unit\":\"회\",\"min\":1,\"max\":7}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\",\"ACCESS_BACKGROUND_LOCATION\"]");
            fixtures = true;
        }
    }

    // ===== 헬퍼 =====

    /** 경로 A(by-template)로 초안을 만들고 (draftId, draft 원본) 을 돌려준다. */
    private MvcResult templateDraft(String token) throws Exception {
        MvcResult res = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", GYM_TEMPLATE));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return res;
    }

    /** draft 응답 그대로(미수정) 생성 요청 본문 조립. */
    private Map<String, Object> createBodyFrom(MvcResult draftRes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draftRes, "$.data.draftId"));
        body.put("title", (String) read(draftRes, "$.data.draft.title"));
        body.put("description", (String) read(draftRes, "$.data.draft.description"));
        body.put("category", (String) read(draftRes, "$.data.draft.category"));
        body.put("mode", (String) read(draftRes, "$.data.draft.mode"));
        body.put("visibility", (Object) read(draftRes, "$.data.draft.visibility"));
        body.put("rankingVisible", (Object) read(draftRes, "$.data.draft.rankingVisible"));
        body.put("capacity", (Integer) read(draftRes, "$.data.draft.capacity"));
        body.put("minTier", (String) read(draftRes, "$.data.draft.minTier"));
        body.put("period", Map.of(
                "start", (String) read(draftRes, "$.data.draft.period.start"),
                "end", (String) read(draftRes, "$.data.draft.period.end")));
        body.put("repeatDays", (List<String>) read(draftRes, "$.data.draft.repeatDays"));
        body.put("params", List.of(Map.of("key", "weekly_count", "value", "3")));
        body.put("verification", Map.of(
                "type", (String) read(draftRes, "$.data.draft.verification.type"),
                "method", (String) read(draftRes, "$.data.draft.verification.method")));
        body.put("penalties", Map.of("watcher", false));
        body.put("imageUrl", null);
        return body;
    }

    private MvcResult create(String token, String idempotencyKey, Map<String, Object> body) throws Exception {
        var builder = post("/api/v1/challenges")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(OM.writeValueAsString(body));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return mvc.perform(builder).andReturn();
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String uploadImage(String token) throws Exception {
        MvcResult res = mvc.perform(multipart("/api/v1/challenges/image")
                        .file(new MockMultipartFile("image", "cover.png", "image/png", pngBytes()))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.imageUrl");
    }

    // =====================================================================
    @Nested
    @DisplayName("멱등키 계약")
    class Idempotency {

        @Test
        @DisplayName("Idempotency-Key 헤더 누락 → 400 IDEMPOTENCY_KEY_REQUIRED")
        void keyRequired() throws Exception {
            String token = memberToken(uniq("idem-req"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            expectError(create(token, null, body), 400, "IDEMPOTENCY_KEY_REQUIRED");
        }

        @Test
        @DisplayName("동일 키 + 동일 본문 재요청 → 기존 201 재응답, 챌린지는 1개만 생성")
        void sameKeySameBody() throws Exception {
            String token = memberToken(uniq("idem-same"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            String key = UUID.randomUUID().toString();

            MvcResult first = create(token, key, body);
            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            String challengeId = read(first, "$.data.challengeId");

            MvcResult second = create(token, key, body);
            assertThat(second.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(second, "$.data.challengeId")).isEqualTo(challengeId);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    Integer.class, challengeId);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("동일 키 + 다른 본문 → 409 IDEMPOTENCY_CONFLICT")
        void sameKeyDifferentBody() throws Exception {
            String token = memberToken(uniq("idem-diff"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            String key = UUID.randomUUID().toString();
            assertThat(create(token, key, body).getResponse().getStatus()).isEqualTo(201);

            Map<String, Object> changed = new LinkedHashMap<>(body);
            changed.put("title", "다른 제목의 챌린지");
            expectError(create(token, key, changed), 409, "IDEMPOTENCY_CONFLICT");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("draftId 원본 대조 — 심사 대상 서버 판정")
    class DraftContrast {

        @Test
        @DisplayName("미수정 생성 → 201 + moderation title/description=EXEMPT·image=NONE + UPCOMING")
        void exemptWhenUnedited() throws Exception {
            String token = memberToken(uniq("cr-exempt"));
            MvcResult res = create(token, UUID.randomUUID().toString(), createBodyFrom(templateDraft(token)));
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(res, "$.data.status")).isEqualTo("UPCOMING");
            assertThat((String) read(res, "$.data.moderation.title")).isEqualTo("EXEMPT");
            assertThat((String) read(res, "$.data.moderation.description")).isEqualTo("EXEMPT");
            assertThat((String) read(res, "$.data.moderation.image")).isEqualTo("NONE");
            assertThat((String) read(res, "$.data.createdAt")).isNotBlank();

            // 인증 스냅샷 + 개인 설정 필요(AUTO)
            assertThat((String) read(res, "$.data.verification.type")).isEqualTo("AUTO");
            assertThat((String) read(res, "$.data.verification.method")).isEqualTo("GPS_PRESENCE");
            assertThat((List<String>) read(res, "$.data.verification.requiredPermissions"))
                    .contains("ACCESS_FINE_LOCATION");
            assertThat((Boolean) read(res, "$.data.personalSetupRequired")).isTrue();

            // DB: ai_title = 원본 제목, 생성자 OWNER 멤버십, 카운터·버전
            String challengeId = read(res, "$.data.challengeId");
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT ai_title, version, participant_count, min_tier, status, repeat_days FROM challenges " +
                            "WHERE id = UNHEX(REPLACE(?, '-', ''))", challengeId);
            assertThat(row.get("ai_title")).isEqualTo("주 3회 헬스장");
            assertThat(((Number) row.get("version")).intValue()).isEqualTo(0);
            assertThat(((Number) row.get("participant_count")).intValue()).isEqualTo(1);
            assertThat(row.get("min_tier")).isEqualTo("BRONZE");
            assertThat(String.valueOf(row.get("repeat_days"))).contains("MON", "SUN");

            Integer owners = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_members WHERE challenge_id = UNHEX(REPLACE(?, '-', '')) " +
                            "AND role = 'OWNER' AND status = 'ACTIVE'", Integer.class, challengeId);
            assertThat(owners).isEqualTo(1);
        }

        @Test
        @DisplayName("제목을 직접 수정 → moderation.title=IN_REVIEW (설명은 EXEMPT), ai_title 은 원본 유지")
        void titleEditedGoesToReview() throws Exception {
            String token = memberToken(uniq("cr-edit"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("title", "내가 고쳐 쓴 제목");

            MvcResult res = create(token, UUID.randomUUID().toString(), body);
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(res, "$.data.moderation.title")).isEqualTo("IN_REVIEW");
            assertThat((String) read(res, "$.data.moderation.description")).isEqualTo("EXEMPT");

            String challengeId = read(res, "$.data.challengeId");
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, ai_title FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", challengeId);
            assertThat(row.get("title")).isEqualTo("내가 고쳐 쓴 제목");
            assertThat(row.get("ai_title")).isEqualTo("주 3회 헬스장");
        }

        @Test
        @DisplayName("무효 draftId → 400 DRAFT_NOT_FOUND / 만료 draftId → 400 DRAFT_EXPIRED")
        void draftInvalidOrExpired() throws Exception {
            String token = memberToken(uniq("cr-draft"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));

            Map<String, Object> unknown = new LinkedHashMap<>(body);
            unknown.put("draftId", UUID.randomUUID().toString());
            expectError(create(token, UUID.randomUUID().toString(), unknown), 400, "DRAFT_NOT_FOUND");

            jdbcTemplate.update("UPDATE challenge_drafts SET expires_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) " +
                    "WHERE id = UNHEX(REPLACE(?, '-', ''))", (String) body.get("draftId"));
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "DRAFT_EXPIRED");
        }

        @Test
        @DisplayName("카테고리는 확인 화면에서도 수정 불가 — draft 와 다르면 400 INVALID_CATEGORY")
        void categoryImmutable() throws Exception {
            String token = memberToken(uniq("cr-cat"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("category", "READING");
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "INVALID_CATEGORY");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("서버 강제값·검증")
    class ServerEnforced {

        @Test
        @DisplayName("AUTO→MANUAL 전환 허용: 점수 패널티 OFF 로 재계산 (클라 penalties 값은 무시)")
        void autoToManualAllowed() throws Exception {
            String token = memberToken(uniq("cr-a2m"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("verification", Map.of("type", "MANUAL", "method", "SELF_CHECK"));

            MvcResult res = create(token, UUID.randomUUID().toString(), body);
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(res, "$.data.verification.type")).isEqualTo("MANUAL");
            assertThat((Boolean) read(res, "$.data.personalSetupRequired")).isFalse();

            String challengeId = read(res, "$.data.challengeId");
            String penalties = jdbcTemplate.queryForObject(
                    "SELECT penalties FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    String.class, challengeId);
            assertThat(penalties.replace(" ", "")).contains("\"score\":false");
        }

        @Test
        @DisplayName("MANUAL 초안(자동 불가 루틴)에서 AUTO 요청 → 400 ROUTINE_AUTO_NOT_SUPPORTED")
        void manualToAutoRejected() throws Exception {
            String token = memberToken(uniq("cr-m2a"));
            // LLM 미매칭 초안(수동 SELF_CHECK)
            LLM_RESPONSE.set("""
                    {"usable":true,"title":"저녁 스트레칭","description":"매일 저녁 스트레칭을 해요.",
                     "category":"EXERCISE","templateId":null,"params":[],
                     "participationType":"SOLO","repeatDays":["MON","TUE","WED","THU","FRI","SAT","SUN"]}
                    """);
            MvcResult draftRes = postJsonAuth("/api/v1/challenges/draft", token,
                    Map.of("description", "매일 저녁 스트레칭을 하고 싶어요"));
            assertThat((String) read(draftRes, "$.data.draft.verification.type")).isEqualTo("MANUAL");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("draftId", (String) read(draftRes, "$.data.draftId"));
            body.put("title", (String) read(draftRes, "$.data.draft.title"));
            body.put("description", (String) read(draftRes, "$.data.draft.description"));
            body.put("category", "EXERCISE");
            body.put("mode", "SOLO");
            body.put("capacity", 50);
            body.put("minTier", "BRONZE");
            body.put("period", Map.of(
                    "start", (String) read(draftRes, "$.data.draft.period.start"),
                    "end", (String) read(draftRes, "$.data.draft.period.end")));
            body.put("params", List.of());
            body.put("verification", Map.of("type", "AUTO", "method", "GPS_PRESENCE"));
            body.put("penalties", Map.of("watcher", false));
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "ROUTINE_AUTO_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("GROUP 생성: visibility 기본 PUBLIC + groupShare=ON, capacity 미지정 400 / 범위 밖 400")
        void groupRules() throws Exception {
            String token = memberToken(uniq("cr-grp"));

            Map<String, Object> ok = createBodyFrom(templateDraft(token));
            ok.put("mode", "GROUP");
            ok.put("visibility", "PUBLIC");
            ok.put("rankingVisible", null);
            MvcResult res = create(token, UUID.randomUUID().toString(), ok);
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            String penalties = jdbcTemplate.queryForObject(
                    "SELECT penalties FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    String.class, (String) read(res, "$.data.challengeId"));
            assertThat(penalties.replace(" ", "")).contains("\"groupShare\":true");

            Map<String, Object> noCapacity = createBodyFrom(templateDraft(token));
            noCapacity.put("mode", "GROUP");
            noCapacity.put("capacity", null);
            expectError(create(token, UUID.randomUUID().toString(), noCapacity), 400, "CAPACITY_REQUIRED");

            Map<String, Object> outOfRange = createBodyFrom(templateDraft(token));
            outOfRange.put("mode", "GROUP");
            outOfRange.put("capacity", 10001);
            expectError(create(token, UUID.randomUUID().toString(), outOfRange), 400, "CAPACITY_OUT_OF_RANGE");
        }

        @Test
        @DisplayName("minTier 가 생성자 표시 티어 초과 → 400 MIN_TIER_EXCEEDS_OWNER")
        void minTierExceedsOwner() throws Exception {
            String token = memberToken(uniq("cr-tier"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("minTier", "GOLD");   // 신규 가입자 표시 티어 = BRONZE
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "MIN_TIER_EXCEEDS_OWNER");
        }

        @Test
        @DisplayName("목표값이 스펙 범위 밖 → 400 INVALID_ROUTINE_PARAM")
        void invalidParam() throws Exception {
            String token = memberToken(uniq("cr-param"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("params", List.of(Map.of("key", "weekly_count", "value", "99")));   // max 7
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "INVALID_ROUTINE_PARAM");
        }

        @Test
        @DisplayName("반복 요일 수정값을 저장하고, 중복·잘못된 요일은 400 INVALID_REPEAT_DAY")
        void repeatDaysValidation() throws Exception {
            String token = memberToken(uniq("cr-repeat"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("repeatDays", List.of("MON", "WED", "FRI"));

            MvcResult res = create(token, UUID.randomUUID().toString(), body);
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            String saved = jdbcTemplate.queryForObject(
                    "SELECT repeat_days FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    String.class, (String) read(res, "$.data.challengeId"));
            assertThat(saved.replace(" ", "")).isEqualTo("[\"MON\",\"WED\",\"FRI\"]");

            Map<String, Object> invalid = createBodyFrom(templateDraft(token));
            invalid.put("repeatDays", List.of("MON", "MON"));
            expectError(create(token, UUID.randomUUID().toString(), invalid), 400, "INVALID_REPEAT_DAY");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("이미지 업로드·소유 검증")
    class ImageOwnership {

        @Test
        @DisplayName("업로드 API 발급 URL 로 생성 → 201 + moderation.image=IN_REVIEW + 등록 시각 기록")
        void ownUploadAccepted() throws Exception {
            String token = memberToken(uniq("img-own"));
            String imageUrl = uploadImage(token);

            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("imageUrl", imageUrl);
            MvcResult res = create(token, UUID.randomUUID().toString(), body);
            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(res, "$.data.moderation.image")).isEqualTo("IN_REVIEW");

            Integer registered = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_image_uploads WHERE image_url = ? AND registered_at IS NOT NULL",
                    Integer.class, imageUrl);
            assertThat(registered).isEqualTo(1);
        }

        @Test
        @DisplayName("임의 외부 URL → 400 INVALID_IMAGE_URL")
        void externalUrlRejected() throws Exception {
            String token = memberToken(uniq("img-ext"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("imageUrl", "https://evil.example.com/x.png");
            expectError(create(token, UUID.randomUUID().toString(), body), 400, "INVALID_IMAGE_URL");
        }

        @Test
        @DisplayName("다른 사용자가 업로드한 URL → 403 IMAGE_NOT_OWNED")
        void othersUploadRejected() throws Exception {
            String other = memberToken(uniq("img-other"));
            String othersUrl = uploadImage(other);

            String token = memberToken(uniq("img-me"));
            Map<String, Object> body = createBodyFrom(templateDraft(token));
            body.put("imageUrl", othersUrl);
            expectError(create(token, UUID.randomUUID().toString(), body), 403, "IMAGE_NOT_OWNED");
        }
    }
}
