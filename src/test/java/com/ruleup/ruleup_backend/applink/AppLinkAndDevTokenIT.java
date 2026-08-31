package com.ruleup.ruleup_backend.applink;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 전체 모듈의 잔여 계약 두 건 — 앱링크 유효성 검사와 개발용 토큰 발급.
 *
 * <p>둘 다 "화면"이 없는 API 라 성격이 다르다.
 * <ul>
 *   <li><b>앱링크 검사</b>는 <b>라우팅 전</b>에 부르는 API 다. 위조·만료된 링크로 상세 화면까지 들어갔다가
 *       에러를 만나는 대신 진입 시점에 걸러 안내 화면으로 보낸다. 그래서 유효하지 않아도 200 이고,
 *       사유를 {@code reason} 으로 내린다 — 에러가 아니라 <b>판정 결과</b>다.</li>
 *   <li><b>개발용 토큰</b>은 <b>인증 우회로</b>다. 놓치면 사고가 아니라 침해라, 이 테스트의 절반이
 *       "막혀 있는가"를 확인한다.</li>
 * </ul>
 */
@SpringBootTest(properties = "app.dev-tokens.secret=test-dev-secret")
@Import(TestcontainersConfiguration.class)
class AppLinkAndDevTokenIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    @Value("${app.app-links.base-url}")
    String linkBaseUrl;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private MvcResult check(String url) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (url != null) body.put("url", url);
        return mvc.perform(post("/api/v1/app-links/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 챌린지 초대 1건을 만들고 링크에 실린 평문 토큰을 돌려준다. */
    private String issueChallengeInvitation(UUID challengeId, UUID inviterId, int expiresInDays) {
        String token = com.ruleup.ruleup_backend.challenge.domain.InvitationTokens.generate();
        jdbc().update("INSERT INTO challenge_invitations (id, challenge_id, inviter_id, token_hash, expires_at) " +
                        "VALUES (?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? DAY))",
                bytes(UUID.randomUUID()), bytes(challengeId), bytes(inviterId),
                com.ruleup.ruleup_backend.challenge.domain.InvitationTokens.hash(token), expiresInDays);
        return token;
    }

    // ================================================================
    @Nested
    @DisplayName("POST /app-links/check — 라우팅 전에 링크를 거른다")
    class AppLinkCheck {

        @Test
        @DisplayName("유효한 챌린지 초대 링크는 타입과 토큰을 돌려준다")
        void validChallengeInvitation() throws Exception {
            Member owner = member("link-ok");
            UUID ch = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            String token = issueChallengeInvitation(ch, owner.id(), 7);

            Map<String, Object> d = read(check(linkBaseUrl + "/c/" + token), "$.data");

            assertThat(d).containsEntry("valid", true)
                    .containsEntry("linkType", "CHALLENGE_INVITATION")
                    .containsEntry("token", token)
                    .containsEntry("reason", null)
                    .containsEntry("expiredAt", null);
        }

        @Test
        @DisplayName("만료된 링크는 valid=false · EXPIRED 와 만료 시각을 함께 내린다")
        void expired() throws Exception {
            Member owner = member("link-expired");
            UUID ch = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            String token = issueChallengeInvitation(ch, owner.id(), -1);   // 어제 만료

            Map<String, Object> d = read(check(linkBaseUrl + "/c/" + token), "$.data");

            assertThat(d).containsEntry("valid", false).containsEntry("reason", "EXPIRED");
            assertThat(d.get("expiredAt")).isNotNull();
            // 만료여도 타입·토큰은 알아냈으므로 내린다 — 클라가 "만료된 초대" 안내를 그릴 수 있다.
            assertThat(d).containsEntry("linkType", "CHALLENGE_INVITATION").containsEntry("token", token);
        }

        @Test
        @DisplayName("위조·삭제된 토큰은 NOT_FOUND")
        void notFound() throws Exception {
            String token = com.ruleup.ruleup_backend.challenge.domain.InvitationTokens.generate();

            Map<String, Object> d = read(check(linkBaseUrl + "/c/" + token), "$.data");

            assertThat(d).containsEntry("valid", false).containsEntry("reason", "NOT_FOUND")
                    .containsEntry("linkType", "CHALLENGE_INVITATION");
        }

        @Test
        @DisplayName("우리 링크가 아니면 MALFORMED — 타입도 토큰도 알 수 없다")
        void malformed() throws Exception {
            Map<String, Object> d = read(check("https://evil.example.com/c/whatever"), "$.data");

            assertThat(d).containsEntry("valid", false).containsEntry("reason", "MALFORMED")
                    .containsEntry("linkType", null).containsEntry("token", null);
        }

        @Test
        @DisplayName("우리 도메인이지만 모르는 경로면 UNSUPPORTED")
        void unsupported() throws Exception {
            Map<String, Object> d = read(check(linkBaseUrl + "/zzz/token"), "$.data");

            assertThat(d).containsEntry("valid", false).containsEntry("reason", "UNSUPPORTED")
                    .containsEntry("linkType", null);
        }

        @Test
        @DisplayName("감시자 초대 링크도 같은 API 로 검사한다")
        void watcherInvitation() throws Exception {
            Member owner = member("link-watcher");
            UUID ch = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            String token = com.ruleup.ruleup_backend.challenge.domain.InvitationTokens.generate();
            jdbc().update("INSERT INTO watcher_invitations " +
                            "(id, token_hash, challenge_id, inviter_user_id, expires_at) " +
                            "VALUES (?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL 7 DAY))",
                    bytes(UUID.randomUUID()),
                    com.ruleup.ruleup_backend.watcher.infra.WatcherHashes.sha256Hex(token),
                    bytes(ch), bytes(owner.id()));

            Map<String, Object> d = read(check(linkBaseUrl + "/w/" + token), "$.data");

            assertThat(d).containsEntry("valid", true).containsEntry("linkType", "WATCHER_INVITATION");
        }

        @Test
        @DisplayName("url 이 없으면 400 APP_LINK_URL_REQUIRED")
        void urlRequired() throws Exception {
            expectError(check(null), 400, "APP_LINK_URL_REQUIRED");
            expectError(check("   "), 400, "APP_LINK_URL_REQUIRED");
        }

        @Test
        @DisplayName("로그인 없이 부를 수 있다 — 앱 진입 시점이라 아직 토큰이 없을 수 있다")
        void isPublic() throws Exception {
            assertThat(check(linkBaseUrl + "/c/none").getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ================================================================
    @Nested
    @DisplayName("POST /dev/tokens — 인증 우회로라 방어가 먼저다")
    class DevTokens {

        private MvcResult issue(String secret, Map<String, Object> body) throws Exception {
            var request = post("/api/v1/dev/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(body == null ? Map.of() : body));
            if (secret != null) request = request.header("X-Dev-Secret", secret);
            return mvc.perform(request).andReturn();
        }

        @Test
        @DisplayName("시크릿이 틀리면 401 이 아니라 404 다 — 경로가 존재한다는 사실을 흘리지 않는다")
        void wrongSecretIsNotFound() throws Exception {
            assertThat(issue("wrong", null).getResponse().getStatus()).isEqualTo(404);
            assertThat(issue(null, null).getResponse().getStatus()).isEqualTo(404);
            // 본문도 내리지 않는다 — 에러 코드가 있으면 그것만으로 존재가 드러난다.
            assertThat(issue("wrong", null).getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("바디를 비우면 온보딩을 마친 테스트 계정을 즉석에서 만든다")
        void createsTestAccount() throws Exception {
            MvcResult res = issue("test-dev-secret", null);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            Map<String, Object> d = read(res, "$.data");
            assertThat(d).containsEntry("created", true);
            assertThat(d.get("accessToken")).isNotNull();
            assertThat(d.get("refreshToken")).isNotNull();

            Map<String, Object> user = (Map<String, Object>) d.get("user");
            // 실계정과 구분되지 않으면 나중에 지울 방법이 없다.
            assertThat(user.get("nickname")).asString().startsWith("test_");
            assertThat(user).containsEntry("status", "ACTIVE")
                    .containsEntry("tier", "BRONZE").containsEntry("score", 10);
        }

        @Test
        @DisplayName("발급한 토큰으로 바로 인증된 요청을 보낼 수 있다 — 운영 토큰과 같은 서명·클레임이다")
        void tokenWorks() throws Exception {
            String at = read(issue("test-dev-secret", null), "$.data.accessToken");

            assertThat(getAuth("/api/v1/me/home", at).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("점수를 주입하면 실제 티어가 구간대로 따라온다 — 최소 입장 티어 분기 재현용")
        void injectsScore() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("score", 1200);

            Map<String, Object> user = read(issue("test-dev-secret", body), "$.data.user");

            assertThat(user).containsEntry("score", 1200)
                    .containsEntry("tier", "RUBY").containsEntry("displayTier", "RUBY");
        }

        @Test
        @DisplayName("tier 는 표시 티어를 덮어써 강등 유예 상태를 재현한다 — 정상 경로로는 사이클을 돌려야 나온다")
        void injectsGraceBand() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("score", 285);      // 실제 티어는 실버
            body.put("tier", "GOLD");    // 표시 티어는 아직 골드(유예)

            Map<String, Object> user = read(issue("test-dev-secret", body), "$.data.user");

            assertThat(user).containsEntry("tier", "SILVER").containsEntry("displayTier", "GOLD");
        }

        @Test
        @DisplayName("제재 상태를 주입해 계정 게이트 분기를 재현한다 — DB 를 손으로 고치지 않아도 된다")
        void injectsSanction() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "SUSPENDED");
            body.put("sanction", Map.of("type", "LOCK", "endsAtAfterDays", 30));

            MvcResult res = issue("test-dev-secret", body);
            String at = read(res, "$.data.accessToken");

            assertThat(read(res, "$.data.user.status").toString()).isEqualTo("SUSPENDED");
            // 잠금 계정은 쓰기가 막힌다 — 게이트가 실제로 걸린다.
            expectError(patchJsonAuth("/api/v1/users/me/profile", at, Map.of("nickname", "바뀐닉")),
                    403, "ACCOUNT_LOCKED");
        }

        @Test
        @DisplayName("agreements=false 면 미동의 상태로 둔다")
        void skipsAgreements() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agreements", false);
            String at = read(issue("test-dev-secret", body), "$.data.accessToken");

            Integer agreed = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_agreement_states s JOIN users u ON u.id = s.user_id " +
                            "WHERE s.agreed = 1 AND u.id = ?", Integer.class,
                    bytes(UUID.fromString(read(issue("test-dev-secret", body), "$.data.user.userId"))));
            assertThat(agreed).isZero();
            assertThat(at).isNotNull();
        }

        @Test
        @DisplayName("userId 를 주면 기존 계정으로 발급한다")
        void reusesExistingAccount() throws Exception {
            Member me = member("dev-existing");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", me.id().toString());

            Map<String, Object> d = read(issue("test-dev-secret", body), "$.data");

            assertThat(d).containsEntry("created", false);
            assertThat((Map<String, Object>) d.get("user")).containsEntry("userId", me.id().toString());
        }

        @Test
        @DisplayName("없는 userId 는 404 USER_NOT_FOUND — 시크릿은 통과한 상태다")
        void unknownUser() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", UUID.randomUUID().toString());

            expectError(issue("test-dev-secret", body), 404, "USER_NOT_FOUND");
        }

        @Test
        @DisplayName("점수 범위 밖이면 400")
        void scoreOutOfRange() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("score", 5000);

            expectError(issue("test-dev-secret", body), 400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("닉네임이 겹치면 서버가 서픽스를 붙여 피한다 — 테스트가 중복으로 깨지면 안 된다")
        void avoidsNicknameCollision() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nickname", "test_같은닉");

            String first = read(issue("test-dev-secret", body), "$.data.user.nickname");
            String second = read(issue("test-dev-secret", body), "$.data.user.nickname");

            assertThat(first).isEqualTo("test_같은닉");
            assertThat(second).isNotEqualTo(first).startsWith("test_");
        }

        @Test
        @DisplayName("스웨거 문서에 노출하지 않는다")
        void hiddenFromDocs() throws Exception {
            String docs = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/v3/api-docs")).andReturn().getResponse().getContentAsString();

            assertThat(docs).doesNotContain("/api/v1/dev/tokens");
        }
    }
}
