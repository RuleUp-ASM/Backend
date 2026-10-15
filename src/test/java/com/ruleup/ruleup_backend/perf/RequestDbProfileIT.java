package com.ruleup.ruleup_backend.perf;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 요청 하나가 DB 에 하는 일을 센다 — 부하테스트(2026-10) 에서 로그인·생성이 과한지 보려고 만들었다.
 * 숫자가 늘면 이 시험이 먼저 알려 준다(상한은 개선 후 실측값).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SqlRecorder.Config.class})
class RequestDbProfileIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @MockitoBean ContentModerationClient moderationClient;
    private MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build(); }

    record Profile(int connections, int commits, List<SqlRecorder.Entry> sql) {
        long count(String verb) { return sql.stream().filter(e -> e.sql().regionMatches(true, 0, verb, 0, verb.length())).count(); }
        void print(String title) {
            System.out.printf("%n==== %s — 커넥션 %d · 커밋 %d · SQL %d (SELECT %d · INSERT %d · UPDATE %d · DELETE %d)%n", title, connections, commits,
                    sql.size(), count("select"), count("insert"), count("update"), count("delete"));
            sql.forEach(e -> System.out.printf("  [%s] %s%n", e.thread(), e.sql().length() > 220 ? e.sql().substring(0, 220) + "…" : e.sql()));
        }
    }

    private Profile profile(ThrowingRunnable r) throws Exception {
        SqlRecorder.start();
        r.run();
        Thread.sleep(1500);   // 커밋 뒤 비동기 리스너까지 기다린다
        List<SqlRecorder.Entry> sql = SqlRecorder.stop();
        return new Profile(SqlRecorder.connections(), SqlRecorder.commits(), sql);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    @Test
    @DisplayName("기존 회원 로그인 · 리프레시 · 템플릿 초안 · 생성의 DB 작업")
    void loginRefreshCreate() throws Exception {
        String tag = uniq("perf");
        signup(tag, "성능" + seq());
        Map<String, Object> login = loginBody(tag, "inst-" + tag, "dev-" + tag);
        postJson("/api/v1/auth/oauth/kakao", login);   // 첫 로그인은 캐시 워밍(동기화 정책 등)

        MvcResult[] res = new MvcResult[1];
        Profile loginP = profile(() -> res[0] = postJson("/api/v1/auth/oauth/kakao", login));
        assertThat(res[0].getResponse().getStatus()).isEqualTo(200);
        loginP.print("기존 회원 로그인(같은 기기)");
        String access = read(res[0], "$.data.accessToken");
        String refresh = read(res[0], "$.data.refreshToken");

        Profile refreshP = profile(() -> res[0] = postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh)));
        assertThat(res[0].getResponse().getStatus()).isEqualTo(200);
        refreshP.print("리프레시");

        Profile draftP = profile(() -> res[0] = postJsonAuth("/api/v1/challenges/recommendation/by-template", access, Map.of("templateId", 1001)));
        assertThat(res[0].getResponse().getStatus()).isEqualTo(200);
        draftP.print("템플릿 초안");
        Map<String, Object> body = createBody(res[0]);

        Profile createP = profile(() -> res[0] = mvc.perform(post("/api/v1/challenges").header("Authorization", "Bearer " + access)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content(OM.writeValueAsString(body))).andReturn());
        assertThat(res[0].getResponse().getStatus()).as(res[0].getResponse().getContentAsString()).isIn(200, 201);
        createP.print("챌린지 생성(공개 그룹)");

        Profile homeP = profile(() -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/home")
                .header("Authorization", "Bearer " + access)).andReturn());
        homeP.print("홈(GET /me/home)");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createBody(MvcResult draft) throws Exception {
        Map<String, Object> d = read(draft, "$.data.draft");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("draftId", read(draft, "$.data.draftId"));
        for (String k : List.of("title", "description", "category", "minTier", "period", "weeklyCount")) b.put(k, d.get(k));
        b.put("mode", "GROUP"); b.put("visibility", "PUBLIC"); b.put("rankingVisible", true); b.put("capacity", 100);
        b.put("params", ((List<Map<String, Object>>) d.get("params")).stream()
                .map(p -> Map.of("key", p.get("key"), "value", String.valueOf(p.get("value")))).collect(Collectors.toList()));
        Map<String, Object> v = (Map<String, Object>) d.get("verification");
        b.put("verification", Map.of("type", v.get("type"), "method", v.get("method")));
        b.put("penalties", Map.of("watcher", false));
        return b;
    }
}
