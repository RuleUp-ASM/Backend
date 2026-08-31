package com.ruleup.ruleup_backend.report;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 신고와 차단 — 방 내부 기능 5-3·5-5, 신고 접수 API 명세(2026-08-26 개편).
 *
 * <p>개편의 요지는 <b>접수 단계에서 판단하지 않는다</b>는 것이다. 임계값·누적 카운트·LLM 접수
 * 필터가 전부 사라졌고, 서버는 <b>차단 등재와 컨텍스트 스냅샷 저장, 전건 적재</b>만 한다.
 * 제재는 운영자가 적재된 건을 검토해 계정 단위로만 내린다.
 *
 * <p>그래서 이 테스트가 확인하는 것의 절반은 <b>"무엇이 일어나지 않는가"</b>다 —
 * 강퇴되지 않고, 카운트가 쌓이지 않고, 방장에게 알림이 가지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportBlockContractIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    @Override
    protected JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    // ===== 헬퍼 =====

    private MvcResult postAuth(String url, String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    private MvcResult deleteAuth(String url, String token) throws Exception {
        return mvc.perform(delete(url).header("Authorization", "Bearer " + token)).andReturn();
    }

    private Map<String, Object> userReport(UUID targetUserId, UUID challengeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", "USER");
        body.put("targetUserId", targetUserId.toString());
        if (challengeId != null) body.put("targetChallengeId", challengeId.toString());
        body.put("contextType", "PROFILE");
        body.put("reason", "INAPPROPRIATE");
        return body;
    }

    private Map<String, Object> challengeReport(UUID challengeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", "CHALLENGE");
        body.put("targetChallengeId", challengeId.toString());
        body.put("contextType", "CHALLENGE_DETAIL");
        body.put("reason", "SPAM_AD");
        return body;
    }

    private int blockCount(UUID blockerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", Integer.class, bytes(blockerId));
    }

    // =====================================================================
    @Nested
    @DisplayName("접수 — 차단 · 스냅샷 · 전건 적재")
    class Submit {

        @Test
        @DisplayName("유저 신고는 201 로 접수되고 즉시 차단이 걸린다")
        void user_report_blocks_immediately() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            MvcResult res = postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));

            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((Boolean) read(res, "$.data.blocked")).isTrue();
            assertThat((String) read(res, "$.data.hiddenEffect")).isEqualTo("USER_CONTENT_MASKED");
            assertThat(blockCount(reporter.id())).isEqualTo(1);
        }

        @Test
        @DisplayName("미참여 챌린지 신고는 탐색에서 숨기고, 참여 중이면 방은 두고 가린다")
        void challenge_hidden_effect_depends_on_participation() throws Exception {
            Member reporter = member(uniq("r"));
            Member owner = member(uniq("o"));
            UUID outside = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID joined = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(joined, reporter.id(), "MEMBER");

            assertThat((String) read(postAuth("/api/v1/reports", reporter.token(),
                    challengeReport(outside)), "$.data.hiddenEffect")).isEqualTo("CHALLENGE_HIDDEN");
            assertThat((String) read(postAuth("/api/v1/reports", reporter.token(),
                    challengeReport(joined)), "$.data.hiddenEffect")).isEqualTo("CHALLENGE_MASKED");
        }

        @Test
        @DisplayName("신고 시점 컨텍스트를 서버가 자동 수집해 스냅샷으로 고정한다")
        void snapshot_is_captured() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            MvcResult res = postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            UUID reportId = UUID.fromString(read(res, "$.data.reportId"));

            String payload = jdbcTemplate.queryForObject(
                    "SELECT payload FROM report_snapshots WHERE report_id = ?", String.class, bytes(reportId));
            assertThat(payload)
                    .as("원본이 수정·삭제돼도 이 값으로 검토한다")
                    .isNotNull()
                    .contains("targetType")
                    .contains("reportedAt");
        }

        @Test
        @DisplayName("자유 텍스트를 받지 않는다 — detail 을 보내도 저장되지 않는다")
        void detail_is_not_accepted() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            Map<String, Object> body = userReport(target.id(), null);
            body.put("detail", "이 문구는 저장되면 안 된다");
            MvcResult res = postAuth("/api/v1/reports", reporter.token(), body);

            assertThat(res.getResponse().getStatus())
                    .as("모르는 필드를 보냈다고 거절하지는 않는다").isEqualTo(201);
            Integer leftover = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'reports' AND column_name = 'detail'", Integer.class);
            assertThat(leftover).as("컬럼 자체가 없어야 저장될 수 없다").isZero();
        }

        @Test
        @DisplayName("본인은 신고할 수 없다 — 400 CANNOT_REPORT_SELF")
        void cannot_report_self() throws Exception {
            Member me = member(uniq("me"));
            expectError(postAuth("/api/v1/reports", me.token(), userReport(me.id(), null)),
                    400, "CANNOT_REPORT_SELF");
        }

        @Test
        @DisplayName("허용되지 않은 사유는 400 INVALID_REPORT_REASON")
        void invalid_reason() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            Map<String, Object> body = userReport(target.id(), null);
            body.put("reason", "NOT_A_REASON");
            expectError(postAuth("/api/v1/reports", reporter.token(), body), 400, "INVALID_REPORT_REASON");
        }

        @Test
        @DisplayName("대상 종류와 ID 가 어긋나면 400 INVALID_REPORT_TARGET")
        void target_mismatch() throws Exception {
            Member reporter = member(uniq("r"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetType", "USER");
            body.put("contextType", "PROFILE");
            body.put("reason", "ETC");
            expectError(postAuth("/api/v1/reports", reporter.token(), body), 400, "INVALID_REPORT_TARGET");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("접수 단계에서 판단하지 않는다 — 무엇이 일어나지 않는가")
    class NoJudgement {

        @Test
        @DisplayName("여러 명이 같은 사람을 신고해도 강퇴되지 않는다 — 누적 카운트가 폐지됐다")
        void accumulation_never_kicks() throws Exception {
            Member owner = member(uniq("o"));
            Member target = member(uniq("t"));
            UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(challengeId, owner.id(), "OWNER");
            insertActiveMembership(challengeId, target.id(), "MEMBER");

            for (int i = 0; i < 6; i++) {
                Member reporter = member(uniq("r" + i));
                insertActiveMembership(challengeId, reporter.id(), "MEMBER");
                Map<String, Object> body = userReport(target.id(), challengeId);
                body.put("contextType", "ROOM");
                assertThat(postAuth("/api/v1/reports", reporter.token(), body)
                        .getResponse().getStatus()).isEqualTo(201);
            }

            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM challenge_members WHERE challenge_id=? AND user_id=?",
                    String.class, bytes(challengeId), bytes(target.id()));
            assertThat(status)
                    .as("신고 경로에서 챌린지 강퇴는 나오지 않는다").isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("적재 자체는 어떤 제재도 발동시키지 않는다")
        void submission_never_sanctions() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sanctions WHERE user_id = ?", Integer.class, bytes(target.id())))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM users WHERE id = ?", String.class, bytes(target.id())))
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("이미 차단한 대상을 다시 신고해도 정상 201 이다 — 오류로 응답하지 않는다")
        void re_report_is_accepted_silently() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            MvcResult second = postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));

            assertThat(second.getResponse().getStatus())
                    .as("신고자에게는 정상 접수로 보여야 한다").isEqualTo(201);
            assertThat((Boolean) read(second, "$.data.blocked")).isTrue();
            // duplicate 플래그는 폐기됐다 — 재신고가 구조적으로 불가능해 내려줄 상태가 없다.
            assertThat(second.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("duplicate");
        }

        @Test
        @DisplayName("건은 하나 더 적재된다 — 차단은 재적용하고 기록은 쌓는다")
        void re_report_still_records() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));

            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reports WHERE reporter_id=? AND target_type='USER' AND target_id=?",
                    Integer.class, bytes(reporter.id()), bytes(target.id()))).isEqualTo(2);
            assertThat(blockCount(reporter.id())).as("차단은 하나다").isEqualTo(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("차단 목록 — 경로가 /blocks 로 개명됐다")
    class Blocks {

        @Test
        @DisplayName("GET /users/me/blocks — 내가 차단한 유저와 챌린지")
        void list_blocks() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            Member owner = member(uniq("o"));
            UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            postAuth("/api/v1/reports", reporter.token(), challengeReport(challengeId));

            MvcResult res = getAuth("/api/v1/users/me/blocks", reporter.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(res, "$.data.users")).hasSize(1);
            assertThat((List<?>) read(res, "$.data.challenges")).hasSize(1);
        }

        @Test
        @DisplayName("구 /blacklist 경로는 사라졌다")
        void legacy_path_is_gone() throws Exception {
            Member m = member(uniq("legacy"));
            assertThat(getAuth("/api/v1/users/me/blacklist", m.token()).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("유저 차단을 해제한다")
        void unblock_user() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));

            MvcResult res = deleteAuth("/api/v1/users/me/blocks/users/" + target.id(), reporter.token());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(blockCount(reporter.id())).isZero();
        }

        @Test
        @DisplayName("차단 해제는 신고 취소가 아니다 — 신고 건과 스냅샷은 그대로 남는다")
        void unblocking_keeps_the_report() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            MvcResult submitted = postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            UUID reportId = UUID.fromString(read(submitted, "$.data.reportId"));

            deleteAuth("/api/v1/users/me/blocks/users/" + target.id(), reporter.token());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reports WHERE id = ?", Integer.class, bytes(reportId))).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM report_snapshots WHERE report_id = ?",
                    Integer.class, bytes(reportId))).isEqualTo(1);
        }

        @Test
        @DisplayName("차단하지 않은 대상을 해제하면 404")
        void unblock_unknown() throws Exception {
            Member m = member(uniq("none"));
            assertThat(deleteAuth("/api/v1/users/me/blocks/users/" + UUID.randomUUID(), m.token())
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("챌린지 차단도 같은 규칙으로 해제된다")
        void unblock_challenge() throws Exception {
            Member reporter = member(uniq("r"));
            Member owner = member(uniq("o"));
            UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            postAuth("/api/v1/reports", reporter.token(), challengeReport(challengeId));

            assertThat(deleteAuth("/api/v1/users/me/blocks/challenges/" + challengeId, reporter.token())
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(blockCount(reporter.id())).isZero();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("신고 기능 정지 — 자동 발동이 아니다")
    class Suspension {

        @Test
        @DisplayName("운영자가 건 기능 정지 중에는 403 ACCOUNT_SUSPENDED")
        void suspended_reporter_blocked() throws Exception {
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            // 신고 남용은 운영자가 확정해 기능 정지로 건다 — 임계값 자동 발동 경로는 없다.
            jdbcTemplate.update("INSERT INTO sanctions " +
                            "(id, user_id, track, type, feature_code, reason_code, reason_text, source, " +
                            " starts_at, ends_at, appeal_used) " +
                            "VALUES (?, ?, 'DISCRETIONARY', 'FEATURE_SUSPENSION', 'REPORT', 'REPORT_ABUSE', " +
                            " '신고 남용', 'DIRECT', NOW(3), DATE_ADD(NOW(3), INTERVAL 7 DAY), 0)",
                    bytes(UUID.randomUUID()), bytes(reporter.id()));
            jdbcTemplate.update("UPDATE users SET status='SUSPENDED' WHERE id=?", bytes(reporter.id()));

            // 계정 게이트는 일반적이지만 클라는 화면별로 분기하므로, 신고 API 명세가 정한
            // 고유 코드를 내린다. 해제 예정 시각도 함께 실어 안내할 수 있게 한다.
            MvcResult res = postAuth("/api/v1/reports", reporter.token(), userReport(target.id(), null));
            expectError(res, 403, "REPORT_SUSPENDED");
            assertThat((String) read(res, "$.error.reason")).isNotBlank();
        }
    }
}
