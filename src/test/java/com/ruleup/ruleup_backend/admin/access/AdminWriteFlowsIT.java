package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 관리자 서비스(role=admin)에서 <b>상태를 바꾸는</b> 관리자 기능이 그대로 동작하는지 — 같은 도메인 로직을 쓰므로
 * 결과가 공개 API 에서 돌던 때와 같아야 한다. 동시에 {@link AdminSqlCapture} 가 실제로 쓴 테이블을 모아
 * 관리자 DB 계정 권한 목록의 근거가 된다(다른 관리자 시험이 다루지 않는 쓰기 경로를 여기서 채운다).
 *
 * <p>DB 는 운영과 같은 제한 계정 {@code ruleup_admin}(grants.sql)으로 붙는다 — 여기서 통과하면 그 권한으로 충분하다.
 */
@AdminRoleTest
class AdminWriteFlowsIT extends ChallengeApiSupport {


    @AfterAll
    static void adminQueriesStayWithinGrants() throws IOException {
        AdminSqlCapture.assertWithinGrants();
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    /** 시험 데이터 준비는 루트로 — 관리자 계정은 챌린지·참여를 만들 권한이 없다. */
    @Override
    protected JdbcTemplate jdbc() {
        return RestrictedAdminDb.root();
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header(CloudflareAccessFilter.ASSERTION_HEADER, AdminRoleTest.ISSUER.issue("writer@ruleup.co.kr"))
                .header("Origin", "https://admin.ruleup.co.kr");
    }

    private MvcResult send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(OM.writeValueAsString(body));
        return mvc.perform(admin(request)).andReturn();
    }

    /** 2단계 확인 — 첫 호출로 토큰을 받고 두 번째에 실어 보낸다. */
    private MvcResult confirmed(String url, Map<String, Object> body) throws Exception {
        MvcResult preview = send(post(url), body);
        assertThat(preview.getResponse().getStatus()).as(url).isEqualTo(428);
        Map<String, Object> withToken = new LinkedHashMap<>(body);
        withToken.put("confirmationToken", read(preview, "$.error.confirmation.token"));
        return send(post(url), withToken);
    }

    /** 공개 API 가 없으니 회원은 직접 만든다. */
    private UUID memberRow(String tag) {
        User u = User.create(OAuthProvider.KAKAO, "write-flow-" + tag + "-" + UUID.randomUUID(), null,
                "w" + (int) (Math.random() * 100_000_000), null, List.of());
        u.approveNickname();
        return userRepository.saveAndFlush(u).getId();
    }

    @Test
    @DisplayName("챌린지 직권 폐쇄 — 참여자 정리와 하드 삭제까지 같은 경로로 끝난다")
    void close_challenge() throws Exception {
        UUID owner = memberRow("owner");
        UUID challengeId = insertChallenge(owner, "EXERCISE", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, owner, "OWNER");

        MvcResult res = confirmed("/api/v1/admin/challenges/" + challengeId + "/close",
                Map.of("reasonText", "반복 위반으로 폐쇄합니다."));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE'",
                Integer.class, (Object) bytes(challengeId))).isZero();
    }

    @Test
    @DisplayName("운영 공지 등록·취소 — 원본만 저장하고 팬아웃은 공개 API 의 잡이 맡는다")
    void notice_create_and_cancel() throws Exception {
        MvcResult created = confirmed("/api/v1/admin/notices", Map.of("title", "점검 안내", "body", "02:00~03:00 점검이 있어요."));
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        String id = read(created, "$.data.announcementId");

        MvcResult cancelled = send(post("/api/v1/admin/notices/" + id + "/cancel"), null);
        assertThat(cancelled.getResponse().getStatus()).as(cancelled.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName("이상탐지 검토")
    void anomaly_review() throws Exception {
        UUID target = memberRow("anomaly");
        UUID signal = UUID.randomUUID();
        RestrictedAdminDb.root().update("INSERT INTO anomaly_signals (id, signal_type, target_user_id, score, detected_at) " +
                "VALUES (?, 'REPORT_ABUSE', ?, 80, NOW(3))", bytes(signal), bytes(target));

        MvcResult res = send(post("/api/v1/admin/anomalies/" + signal + "/review"), Map.of("note", "오탐 확인"));
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName("제재 집행·해제 — 참여 정리는 아웃박스에 쌓이고 공개 API 스윕이 흘린다")
    void sanction_apply_and_revoke() throws Exception {
        UUID target = memberRow("sanction");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "LOCK");
        body.put("reasonCode", "REPORT_CONFIRMED");
        body.put("reasonText", "신고 검토 결과 커뮤니티 가이드 위반이 확인되었습니다.");
        body.put("source", "DIRECT");

        MvcResult applied = confirmed("/api/v1/admin/users/" + target + "/sanctions", body);
        assertThat(applied.getResponse().getStatus()).as(applied.getResponse().getContentAsString()).isEqualTo(200);
        String sanctionId = read(applied, "$.data.sanctionId");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE processed_at IS NULL AND payload LIKE ?",
                Integer.class, "%" + target + "%")).as("관리자 서비스는 쌓기만 한다").isPositive();

        MvcResult revoked = send(delete("/api/v1/admin/users/" + target + "/sanctions/" + sanctionId),
                Map.of("reasonText", "재검토 인용 — 오탐으로 확인됨"));
        assertThat(revoked.getResponse().getStatus()).as(revoked.getResponse().getContentAsString()).isEqualTo(200);
    }
}
