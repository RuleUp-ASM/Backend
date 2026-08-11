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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 챌린지 생성 진입 초안 파이프라인 계약 테스트 — API 명세(챌린지 생성 모듈) 기준.
 *
 *  [1] POST /api/v1/challenges/draft — 경로 B(설명 입력 → LLM 5-Step)
 *      - 입력: description 만(1~200자). 누락/초과 400.
 *      - LLM 통과: result=OK + draftId(24h 보관, origin=AI) + draft(전체 초안 스키마).
 *      - Step 1·2 차단: 200 result=FALLBACK (에러 아님) + message + draftId/draft null.
 *      - LLM 장애·파싱 실패: 기본 템플릿 대체(result=OK, 수동 SELF_CHECK 초안).
 *      - Step 0: 사용자당 1분 10회 초과 → 429 RECOMMENDATION_RATE_LIMITED.
 *  [2] POST /api/v1/challenges/recommendation/by-template — 경로 A(추천 탭, LLM 미경유)
 *      - draft 응답과 동일 스키마 + draftId(origin=TEMPLATE).
 *      - templateId 누락 400 TEMPLATE_ID_REQUIRED / 미존재 404 TEMPLATE_NOT_FOUND.
 *  [3] GET /api/v1/challenges/recommendations — "지금 시작하기 좋은 루틴" 3개 상시 보장
 *      - 항상 3개(진행 중 카테고리 제외를 풀어서라도), 필드 계약, limit 파라미터 폐기.
 */
@SpringBootTest(properties = "app.llm.fake=false")
@Import({TestcontainersConfiguration.class, ChallengeDraftPipelineIT.StubLlmConfig.class})
class ChallengeDraftPipelineIT extends ChallengeApiSupport {

    /** 테스트가 응답을 갈아끼우는 LLM 스텁. null = LLM 장애(폴백 경로). */
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

    private static final long WAKE_TEMPLATE = 9101L;
    private static final long GYM_TEMPLATE = 9102L;
    private static final long WALK_TEMPLATE = 9103L;
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        LLM_RESPONSE.set(null);
        if (!fixtures) {
            // 루틴 테이블은 시드 없음(스키마만) — 테스트가 자동 인증 루틴 3건을 직접 채운다(12종 카테고리).
            insertAutoTemplate(WAKE_TEMPLATE, "아침 기상", "일찍 일어나는 습관", "WAKE_SLEEP",
                    "{\"target_time\":{\"default\":\"07:00\",\"unit\":\"hh:mm\"}}",
                    "WAKE", "[\"PACKAGE_USAGE_STATS\"]");
            insertAutoTemplate(GYM_TEMPLATE, "주 3회 헬스장", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"weekly_count\":{\"default\":3,\"unit\":\"회\",\"min\":1,\"max\":7}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\",\"ACCESS_BACKGROUND_LOCATION\"]");
            insertAutoTemplate(WALK_TEMPLATE, "매일 만보 걷기", "걷기 습관", "EXERCISE",
                    "{\"steps\":{\"default\":10000,\"unit\":\"보\",\"min\":1000,\"max\":50000}}",
                    "HEALTH", "[\"ACTIVITY_RECOGNITION\"]");
            fixtures = true;
        }
    }

    private static String llmOk(Long templateId) {
        return """
                {"usable":true,
                 "title":"매일 아침 6시 기상",
                 "description":"아침형 인간이 되어 하루를 길게 쓰는 습관을 만들어요.",
                 "category":"WAKE_SLEEP",
                 "templateId":%s,
                 "params":[{"key":"target_time","value":"06:00"}],
                 "participationType":"SOLO",
                 "repeatDays":["MON","TUE","WED","THU","FRI","SAT","SUN"]}
                """.formatted(templateId == null ? "null" : templateId.toString());
    }

    // =====================================================================
    @Nested
    @DisplayName("POST /api/v1/challenges/draft — 경로 B(LLM 5-Step)")
    class DraftApi {

        @Test
        @DisplayName("설명 누락·빈 값 → 400 ROUTINE_DESCRIPTION_REQUIRED")
        void descriptionRequired() throws Exception {
            String token = memberToken(uniq("draft-req"));
            expectError(postJsonAuth("/api/v1/challenges/draft", token, Map.of()),
                    400, "ROUTINE_DESCRIPTION_REQUIRED");
            expectError(postJsonAuth("/api/v1/challenges/draft", token, Map.of("description", "  ")),
                    400, "ROUTINE_DESCRIPTION_REQUIRED");
        }

        @Test
        @DisplayName("설명 200자 초과 → 400 ROUTINE_DESCRIPTION_TOO_LONG")
        void descriptionTooLong() throws Exception {
            String token = memberToken(uniq("draft-long"));
            expectError(postJsonAuth("/api/v1/challenges/draft", token,
                            Map.of("description", "가".repeat(201))),
                    400, "ROUTINE_DESCRIPTION_TOO_LONG");
        }

        @Test
        @DisplayName("LLM 통과 → result=OK + draftId + 전체 초안(제목·교정 설명·카테고리·서버 기본값)")
        void draftOk() throws Exception {
            String token = memberToken(uniq("draft-ok"));
            LLM_RESPONSE.set(llmOk(WAKE_TEMPLATE));

            MvcResult res = postJsonAuth("/api/v1/challenges/draft", token,
                    Map.of("description", "아침형 인간이 되어 하루를 길게 쓰는 습관을 만들고 싶어요"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("OK");

            String draftId = read(res, "$.data.draftId");
            assertThat(draftId).isNotBlank();

            // AI 생성 제목·교정 설명·분류 카테고리
            assertThat((String) read(res, "$.data.draft.title")).isEqualTo("매일 아침 6시 기상");
            assertThat((String) read(res, "$.data.draft.description")).contains("아침형 인간");
            assertThat((String) read(res, "$.data.draft.category")).isEqualTo("WAKE_SLEEP");

            // 서버 기본값: 솔로·정원 50·랭킹 노출 true·visibility null
            assertThat((String) read(res, "$.data.draft.mode")).isEqualTo("SOLO");
            assertThat((Object) read(res, "$.data.draft.visibility")).isNull();
            assertThat((Boolean) read(res, "$.data.draft.rankingVisible")).isTrue();
            assertThat((Integer) read(res, "$.data.draft.capacity")).isEqualTo(50);

            // minTier = 생성자 표시 티어(신규 가입자 BRONZE)
            assertThat((String) read(res, "$.data.draft.minTier")).isEqualTo("BRONZE");

            // 기간: 시작 = 생성일 +1일, 종료 = 시작 +2주
            LocalDate start = LocalDate.parse(read(res, "$.data.draft.period.start"));
            LocalDate end = LocalDate.parse(read(res, "$.data.draft.period.end"));
            // 날짜 축은 KST — 시스템 기본 타임존으로 단정하면 UTC로 도는 CI에서 하루 어긋난다.
            assertThat(start).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1));
            assertThat(end).isEqualTo(start.plusDays(14));

            // 일정: 생성 진입에서 반복 요일과 주간 빈도수를 함께 내려준다.
            assertThat((List<String>) read(res, "$.data.draft.repeatDays"))
                    .containsExactly("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            assertThat((Integer) read(res, "$.data.draft.weeklyCount")).isEqualTo(7);

            // 목표값: 템플릿 스펙 + LLM 값 병합
            assertThat((String) read(res, "$.data.draft.params[0].key")).isEqualTo("target_time");
            assertThat((String) read(res, "$.data.draft.params[0].value")).isEqualTo("06:00");
            assertThat((String) read(res, "$.data.draft.params[0].defaultValue")).isEqualTo("07:00");
            assertThat((String) read(res, "$.data.draft.params[0].kind")).isEqualTo("TIME");
            assertThat((String) read(res, "$.data.draft.params[0].unit")).isEqualTo("hh:mm");

            // 인증: 루틴 매칭 → AUTO + method + 필요 권한
            assertThat((String) read(res, "$.data.draft.verification.type")).isEqualTo("AUTO");
            assertThat((String) read(res, "$.data.draft.verification.method")).isEqualTo("WAKE");
            assertThat((List<String>) read(res, "$.data.draft.verification.requiredPermissions"))
                    .contains("PACKAGE_USAGE_STATS");

            // 패널티: score = AUTO 고정 ON, groupShare = 솔로 OFF, watcher 기본 false
            assertThat((Boolean) read(res, "$.data.draft.penalties.score")).isTrue();
            assertThat((Boolean) read(res, "$.data.draft.penalties.groupShare")).isFalse();
            assertThat((Boolean) read(res, "$.data.draft.penalties.watcher")).isFalse();

            // 원본 초안 DB 보관: origin=AI, 24시간 만료, 제목=AI 제목
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT origin, title, template_id, " +
                            " TIMESTAMPDIFF(MINUTE, created_at, expires_at) AS ttl_minutes " +
                            "FROM challenge_drafts WHERE id = UNHEX(REPLACE(?, '-', ''))", draftId);
            assertThat(row.get("origin")).isEqualTo("AI");
            assertThat(row.get("title")).isEqualTo("매일 아침 6시 기상");
            assertThat(((Number) row.get("template_id")).longValue()).isEqualTo(WAKE_TEMPLATE);
            // 24시간 보관(생성-저장 사이 시계 오차 1분 허용)
            assertThat(((Number) row.get("ttl_minutes")).intValue()).isBetween(24 * 60 - 1, 24 * 60);
        }

        @Test
        @DisplayName("Step 1·2 차단 → 200 result=FALLBACK + message (draftId·draft null, 에러 아님)")
        void fallback() throws Exception {
            String token = memberToken(uniq("draft-fb"));
            LLM_RESPONSE.set("{\"usable\":false,\"rejectType\":\"MEANINGLESS\",\"rejectReason\":\"의미 없는 입력\"}");

            MvcResult res = postJsonAuth("/api/v1/challenges/draft", token,
                    Map.of("description", "습관이 되고 싶은 무언가를 적어봅니다"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("FALLBACK");
            assertThat((Object) read(res, "$.data.draftId")).isNull();
            assertThat((Object) read(res, "$.data.draft")).isNull();
            assertThat((String) read(res, "$.data.message")).isNotBlank();
        }

        @Test
        @DisplayName("LLM 장애(무응답) → FALLBACK: 초안·draftId 없음 (사용자 원문이 AI 면제 경로로 새지 않는다)")
        void llmDown() throws Exception {
            String token = memberToken(uniq("draft-down"));
            LLM_RESPONSE.set(null);

            MvcResult res = postJsonAuth("/api/v1/challenges/draft", token,
                    Map.of("description", "매일 저녁 스트레칭을 하고 싶어요"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.result")).isEqualTo("FALLBACK");
            assertThat((Object) read(res, "$.data.draftId")).isNull();
            assertThat((Object) read(res, "$.data.draft")).isNull();
            assertThat((String) read(res, "$.data.message")).isNotBlank();
        }

        @Test
        @DisplayName("LLM 장애 시 초안을 저장하지 않는다 — 원문이 origin=AI 로 남으면 심사 면제(EXEMPT) 우회가 된다")
        void llmDownPersistsNothing() throws Exception {
            String token = memberToken(uniq("draft-down-db"));
            LLM_RESPONSE.set(null);
            String rawDescription = "심사를 우회하려는 문장 " + UUID.randomUUID();

            postJsonAuth("/api/v1/challenges/draft", token, Map.of("description", rawDescription));

            Integer saved = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM challenge_drafts WHERE description = ?", Integer.class, rawDescription);
            assertThat(saved).isZero();
        }

        @Test
        @DisplayName("LLM 반복 요일 3개 → repeatDays와 weeklyCount=3을 생성 진입 응답에 노출")
        void weeklyFrequencyFromRepeatDays() throws Exception {
            String token = memberToken(uniq("draft-freq"));
            LLM_RESPONSE.set("""
                    {"usable":true,"title":"주 3회 아침 운동","description":"월수금 아침에 운동해요.",
                     "category":"EXERCISE","templateId":null,"params":[],"participationType":"SOLO",
                     "repeatDays":["MON","WED","FRI"]}
                    """);

            MvcResult res = postJsonAuth("/api/v1/challenges/draft", token,
                    Map.of("description", "월수금 아침마다 운동하고 싶어요"));

            assertThat((List<String>) read(res, "$.data.draft.repeatDays"))
                    .containsExactly("MON", "WED", "FRI");
            assertThat((Integer) read(res, "$.data.draft.weeklyCount")).isEqualTo(3);
        }

        @Test
        @DisplayName("Step 0 rate limit: 1분 10회 초과 → 429 RECOMMENDATION_RATE_LIMITED")
        void rateLimited() throws Exception {
            String token = memberToken(uniq("draft-rl"));
            LLM_RESPONSE.set(llmOk(null));
            for (int i = 0; i < 10; i++) {
                MvcResult ok = postJsonAuth("/api/v1/challenges/draft", token,
                        Map.of("description", "매일 아침 물 한 잔 마시기 " + i));
                assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            }
            expectError(postJsonAuth("/api/v1/challenges/draft", token,
                            Map.of("description", "매일 아침 물 한 잔 마시기 11")),
                    429, "RECOMMENDATION_RATE_LIMITED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("POST /api/v1/challenges/recommendation/by-template — 경로 A(LLM 미경유)")
    class ByTemplateApi {

        @Test
        @DisplayName("templateId 누락 → 400 TEMPLATE_ID_REQUIRED")
        void templateIdRequired() throws Exception {
            String token = memberToken(uniq("tpl-req"));
            expectError(postJsonAuth("/api/v1/challenges/recommendation/by-template", token, Map.of()),
                    400, "TEMPLATE_ID_REQUIRED");
        }

        @Test
        @DisplayName("존재하지 않는 템플릿 → 404 TEMPLATE_NOT_FOUND")
        void templateNotFound() throws Exception {
            String token = memberToken(uniq("tpl-404"));
            expectError(postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                            Map.of("templateId", 999999L)),
                    404, "TEMPLATE_NOT_FOUND");
        }

        @Test
        @DisplayName("템플릿 기본값 초안 + draftId(origin=TEMPLATE) — draft API와 동일 스키마")
        void byTemplate() throws Exception {
            String token = memberToken(uniq("tpl-ok"));
            MvcResult res = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                    Map.of("templateId", GYM_TEMPLATE));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            String draftId = read(res, "$.data.draftId");
            assertThat(draftId).isNotBlank();

            // 템플릿 기본값 — 제목 = 템플릿명(임시 제목 역할)
            assertThat((String) read(res, "$.data.draft.title")).isEqualTo("주 3회 헬스장");
            assertThat((String) read(res, "$.data.draft.category")).isEqualTo("EXERCISE");
            assertThat((String) read(res, "$.data.draft.mode")).isEqualTo("SOLO");
            assertThat((Integer) read(res, "$.data.draft.capacity")).isEqualTo(50);
            assertThat((String) read(res, "$.data.draft.minTier")).isEqualTo("BRONZE");
            assertThat((List<String>) read(res, "$.data.draft.repeatDays"))
                    .containsExactly("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
            assertThat((Integer) read(res, "$.data.draft.weeklyCount")).isEqualTo(7);

            // 목표값 = 템플릿 기본값
            assertThat((String) read(res, "$.data.draft.params[0].key")).isEqualTo("weekly_count");
            assertThat((String) read(res, "$.data.draft.params[0].value")).isEqualTo("3");
            assertThat((String) read(res, "$.data.draft.params[0].kind")).isEqualTo("NUMBER");

            // 자동 인증 루틴 → AUTO + 권한 목록
            assertThat((String) read(res, "$.data.draft.verification.type")).isEqualTo("AUTO");
            assertThat((String) read(res, "$.data.draft.verification.method")).isEqualTo("GPS_PRESENCE");
            assertThat((List<String>) read(res, "$.data.draft.verification.requiredPermissions"))
                    .contains("ACCESS_FINE_LOCATION");
            assertThat((Boolean) read(res, "$.data.draft.penalties.score")).isTrue();

            // 원본 초안 보관: origin=TEMPLATE
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT origin, template_id FROM challenge_drafts WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    draftId);
            assertThat(row.get("origin")).isEqualTo("TEMPLATE");
            assertThat(((Number) row.get("template_id")).longValue()).isEqualTo(GYM_TEMPLATE);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("GET /api/v1/challenges/recommendations — 추천 3개 상시 보장")
    class RecommendationsApi {

        @Test
        @DisplayName("항상 3개 반환 + 필드 계약(templateId·title·category·verificationType=AUTO·reason)")
        void alwaysThree() throws Exception {
            String token = memberToken(uniq("rec-3"));
            MvcResult res = getAuth("/api/v1/challenges/recommendations", token);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            List<Map<String, Object>> items = read(res, "$.data.items");
            assertThat(items).hasSize(3);
            for (Map<String, Object> item : items) {
                assertThat(item.get("templateId")).isNotNull();
                assertThat((String) item.get("title")).isNotBlank();
                assertThat((String) item.get("category")).isNotBlank();
                assertThat(item.get("verificationType")).isEqualTo("AUTO");
                assertThat((String) item.get("reason")).isNotBlank();
            }
        }

        @Test
        @DisplayName("진행 중 카테고리 루틴은 제외 — 단 3개를 못 채우면 제외를 풀어서라도 3개 보장")
        void excludeActiveCategoryButGuaranteeThree() throws Exception {
            Member m = member(uniq("rec-ex"));
            // 이 회원은 WAKE_SLEEP 챌린지 진행 중 → WAKE_SLEEP 제외하고도 EXERCISE 2개뿐이라
            // 3개 보장을 위해 제외를 풀고 채운다(전체 템플릿 3개 픽스처 기준).
            var challengeId = insertChallenge(m.id(), "WAKE_SLEEP", "ACTIVE", "SOLO");
            insertActiveMembership(challengeId, m.id(), "OWNER");

            MvcResult res = getAuth("/api/v1/challenges/recommendations", m.token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            List<Map<String, Object>> items = read(res, "$.data.items");
            assertThat(items).hasSize(3);
        }
    }
}
