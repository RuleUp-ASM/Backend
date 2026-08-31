package com.ruleup.ruleup_backend.admin;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import com.ruleup.ruleup_backend.admin.repository.AdminAuditLogRepository;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.sanction.SanctionRepository;
import com.ruleup.ruleup_backend.sanction.domain.SanctionTrack;
import com.ruleup.ruleup_backend.sanction.domain.SanctionType;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 운영자 백오피스 — 공통 5-2·5-3·5-5, 백엔드 4-2.
 *
 * <p>이 모듈이 존재하는 이유는 하나다 — <b>페이지1의 모든 계정 제재는 사람의 검토를 반드시
 * 거치도록 설계돼 있다.</b> 신고는 임계값 없이 전건 적재되고 적재 자체는 어떤 제재도 발동시키지
 * 않으며, 이상탐지도 탐지만으로는 제재하지 않는다. 그 유일한 경로의 도구가 여기다.
 *
 * <p>네 개의 가드레일을 각각 테스트한다.
 * <ol>
 *   <li>일반 회원 계정의 접근 성공 <b>0건</b></li>
 *   <li>조작 이력이 남지 않는 제재 <b>0건</b> — 재검토 대응의 유일한 근거다</li>
 *   <li>검토 없이 발동된 계정 제재 <b>0건</b> — 잠금·영구 정지는 직권 전용이다</li>
 *   <li>고지 없이 집행된 직권 제재 <b>0건</b> — 긴급 선조치도 사후 고지가 필수다</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminBackofficeIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired SanctionRepository sanctionRepository;
    @Autowired AdminAuditLogRepository auditLogRepository;
    @Autowired NotificationRepository notificationRepository;

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

    /** 운영자 롤을 부여한 계정. */
    private Member operator(String tag) throws Exception {
        Member m = member(uniq(tag));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(m.id()));
        return m;
    }

    private MvcResult postAuth(String url, String token, Map<String, Object> body) throws Exception {
        var req = post(url).header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body));
        return mvc.perform(req).andReturn();
    }

    /** 2단계 확인을 거쳐 실행한다 — 첫 호출로 토큰을 받고 두 번째에 실어 보낸다. */
    private MvcResult confirmAndPost(String url, String token, Map<String, Object> body) throws Exception {
        MvcResult preview = postAuth(url, token, body);
        assertThat(preview.getResponse().getStatus())
                .as("확인 토큰 없이는 실행되지 않는다").isEqualTo(428);

        Map<String, Object> confirmed = new java.util.LinkedHashMap<>(body);
        confirmed.put("confirmationToken", read(preview, "$.error.confirmationToken"));
        return postAuth(url, token, confirmed);
    }

    private Map<String, Object> sanctionBody(String type) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", type);
        body.put("reasonCode", "REPORT_CONFIRMED");
        body.put("reasonText", "신고 검토 결과 커뮤니티 가이드 위반이 확인되었습니다.");
        body.put("source", "DIRECT");
        return body;
    }

    /** 신고 1건 적재 — 접수는 방 내부 모듈이 하고 백오피스는 읽기만 한다. */
    private UUID insertReport(UUID reporterId, UUID targetUserId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reports " +
                        "(id, reporter_id, target_type, target_user_id, context_type, reason, detail) " +
                        "VALUES (?, ?, 'USER', ?, 'PROFILE', 'ABUSE', '테스트 신고')",
                bytes(id), bytes(reporterId), bytes(targetUserId));
        jdbcTemplate.update("INSERT INTO report_snapshots (report_id, payload) VALUES (?, ?)",
                bytes(id), "{\"nickname\":\"피신고자\",\"content\":\"신고 시점 스냅샷\"}");
        return id;
    }

    // =====================================================================
    @Nested
    @DisplayName("접근 통제 — 일반 회원 접근 성공 0건")
    class Access {

        @Test
        @DisplayName("운영자가 아니면 403 ADMIN_FORBIDDEN")
        void member_cannot_access() throws Exception {
            Member normal = member(uniq("normal"));
            expectError(getAuth("/api/v1/admin/reports", normal.token()), 403, "ADMIN_FORBIDDEN");
        }

        @Test
        @DisplayName("거부도 감사 로그에 남는다 — DENIED 급증이 우회 시도의 신호다")
        void denied_is_logged() throws Exception {
            Member normal = member(uniq("denied"));
            getAuth("/api/v1/admin/reports", normal.token());

            assertThat(auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(normal.id()))
                    .as("운영자가 아닌 계정 ID 도 조작자로 들어올 수 있다")
                    .isNotEmpty()
                    .allMatch(l -> l.getResult() == AdminAuditLog.Result.DENIED);
        }

        @Test
        @DisplayName("미인증은 401 LOGIN_REQUIRED")
        void unauthenticated_401() throws Exception {
            expectError(mvc.perform(get("/api/v1/admin/reports")).andReturn(), 401, "LOGIN_REQUIRED");
        }

        @Test
        @DisplayName("운영자는 통과하고 그 조회도 기록된다 — 읽기만 해도 남긴다")
        void operator_access_is_logged() throws Exception {
            Member op = operator("op");
            assertThat(getAuth("/api/v1/admin/reports", op.token()).getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(op.id()))
                    .anyMatch(l -> l.getAction() == AdminAction.REPORT_QUEUE_VIEW
                            && l.getResult() == AdminAuditLog.Result.ALLOWED);
        }

        @Test
        @DisplayName("요청 본문은 통째로 남기지 않고 다이제스트만 남긴다 — 민감정보가 로그로 새지 않게")
        void payload_is_digested() throws Exception {
            Member op = operator("digest");
            Member target = member(uniq("t"));

            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            AdminAuditLog log = auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(op.id())
                    .stream().filter(l -> l.getAction() == AdminAction.SANCTION_APPLY)
                    .findFirst().orElseThrow();
            assertThat(log.getPayloadDigest()).hasSize(64);   // SHA-256 hex
            assertThat(log.getPayloadDigest()).doesNotContain("커뮤니티 가이드");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("2단계 확인 — 서버가 요구하지 않으면 클라이언트 모달만으로는 못 막는다")
    class Confirmation {

        @Test
        @DisplayName("확인 토큰 없이 제재를 집행하면 428 CONFIRMATION_REQUIRED")
        void sanction_requires_confirmation() throws Exception {
            Member op = operator("confirm");
            Member target = member(uniq("t"));

            MvcResult res = postAuth("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            expectError(res, 428, "CONFIRMATION_REQUIRED");
            assertThat(sanctionRepository.findByUserIdOrderByStartsAtDesc(target.id()))
                    .as("확인 전에는 아무것도 집행되지 않는다").isEmpty();
        }

        @Test
        @DisplayName("428 응답에 대상·사유·기간을 재제시한다 — 무엇을 확인하는지 보여줘야 한다")
        void preview_shows_what_is_confirmed() throws Exception {
            Member op = operator("preview");
            Member target = member(uniq("t"));

            MvcResult res = postAuth("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            assertThat((String) read(res, "$.error.confirmationToken")).isNotBlank();
            assertThat((String) read(res, "$.error.preview.targetNickname")).isNotBlank();
            assertThat((String) read(res, "$.error.preview.type")).isEqualTo("LOCK");
        }

        @Test
        @DisplayName("다른 요청의 토큰은 통하지 않는다 — 대상·내용이 바뀌면 다시 확인해야 한다")
        void token_is_bound_to_the_request() throws Exception {
            Member op = operator("bound");
            Member first = member(uniq("t1"));
            Member second = member(uniq("t2"));

            MvcResult preview = postAuth("/api/v1/admin/users/" + first.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));
            String stolen = read(preview, "$.error.confirmationToken");

            Map<String, Object> body = sanctionBody("LOCK");
            body.put("confirmationToken", stolen);
            expectError(postAuth("/api/v1/admin/users/" + second.id() + "/sanctions", op.token(), body),
                    428, "CONFIRMATION_REQUIRED");
        }

        @Test
        @DisplayName("직권 폐쇄는 영향 인원 수를 먼저 응답한다 — 오조작을 막는 정보다")
        void close_previews_affected_count() throws Exception {
            Member op = operator("close");
            Member owner = member(uniq("owner"));
            UUID challengeId = insertChallenge(owner.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(challengeId, owner.id(), "OWNER");

            MvcResult res = postAuth("/api/v1/admin/challenges/" + challengeId + "/close",
                    op.token(), Map.of("reasonText", "반복 위반으로 폐쇄합니다."));

            expectError(res, 428, "CONFIRMATION_REQUIRED");
            assertThat((Integer) read(res, "$.error.preview.affectedMemberCount")).isEqualTo(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("제재 집행 — 상태 전이 커밋 후 아웃박스")
    class Sanction {

        @Test
        @DisplayName("집행하면 제재가 남고 계정이 SUSPENDED 로 전이한다")
        void applies_sanction_and_transitions_status() throws Exception {
            Member op = operator("apply");
            Member target = member(uniq("t"));

            MvcResult res = confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            assertThat(sanctionRepository.findByUserIdOrderByStartsAtDesc(target.id()))
                    .singleElement()
                    .satisfies(s -> {
                        assertThat(s.getType()).isEqualTo(SanctionType.LOCK);
                        assertThat(s.getTrack())
                                .as("운영자 집행은 직권 트랙이다").isEqualTo(SanctionTrack.DISCRETIONARY);
                        assertThat(s.getOperatorId()).isEqualTo(op.id());
                    });
            assertThat(userRepository.findById(target.id()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.SUSPENDED);
        }

        @Test
        @DisplayName("고지 없이 집행된 직권 제재가 0건이다 — 필수(A) 알림이 함께 나간다")
        void never_sanctions_without_notice() throws Exception {
            Member op = operator("notice");
            Member target = member(uniq("t"));

            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            assertThat(sanctionRepository.findByUserIdOrderByStartsAtDesc(target.id()))
                    .singleElement()
                    .satisfies(s -> assertThat(s.getNotifiedAt())
                            .as("null 이면 가드레일 위반이다").isNotNull());
            assertThat(notificationRepository.findInbox(target.id(), null, null, Limit.unlimited()))
                    .anyMatch(n -> NotificationType.ACCOUNT_SANCTION.name().equals(n.getType()));
            assertThat(sanctionRepository.countByNotifiedAtIsNullAndTrack(SanctionTrack.DISCRETIONARY))
                    .isZero();
        }

        @Test
        @DisplayName("조작 이력이 남지 않는 제재가 0건이다")
        void never_sanctions_without_audit_log() throws Exception {
            Member op = operator("audit");
            Member target = member(uniq("t"));

            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
                    AdminAuditLog.TargetType.USER, target.id()))
                    .anyMatch(l -> l.getAction() == AdminAction.SANCTION_APPLY
                            && l.getResult() == AdminAuditLog.Result.ALLOWED);
        }

        @Test
        @DisplayName("같은 수준의 제재가 이미 있으면 409 SANCTION_ALREADY_ACTIVE")
        void duplicate_sanction_rejected() throws Exception {
            Member op = operator("dup");
            Member target = member(uniq("t"));
            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            MvcResult second = confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));
            expectError(second, 409, "SANCTION_ALREADY_ACTIVE");
        }

        @Test
        @DisplayName("영구 정지는 밴리스트에 해시를 남긴다")
        void ban_records_hash() throws Exception {
            Member op = operator("ban");
            Member target = member(uniq("t"));
            long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ban_list", Long.class);

            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("BAN"));

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ban_list", Long.class))
                    .isEqualTo(before + 1);
        }

        @Test
        @DisplayName("사유 입력은 필수다 — 고지 알림과 재검토 대응의 근거다")
        void reason_text_required() throws Exception {
            Member op = operator("noreason");
            Member target = member(uniq("t"));

            Map<String, Object> body = sanctionBody("LOCK");
            body.remove("reasonText");
            expectError(postAuth("/api/v1/admin/users/" + target.id() + "/sanctions", op.token(), body),
                    400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("제재 해제는 재검토 인용이며 계정을 되돌린다")
        void revoke_restores_account() throws Exception {
            Member op = operator("revoke");
            Member target = member(uniq("t"));
            MvcResult applied = confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));
            String sanctionId = read(applied, "$.data.sanctionId");

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/admin/users/" + target.id() + "/sanctions/" + sanctionId)
                    .header("Authorization", "Bearer " + op.token())).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            assertThat(userRepository.findById(target.id()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("신고 검토 — 적재된 것을 읽고 상태만 종결한다")
    class Review {

        @Test
        @DisplayName("검토 큐는 대상 단위로 묶어 내린다 — 판단 속도를 위해서다")
        void queue_is_grouped_by_target() throws Exception {
            Member op = operator("queue");
            Member target = member(uniq("t"));
            insertReport(member(uniq("r1")).id(), target.id());
            insertReport(member(uniq("r2")).id(), target.id());

            MvcResult res = getAuth("/api/v1/admin/reports", op.token());
            List<Map<String, Object>> groups = read(res, "$.data.items");

            assertThat(groups).filteredOn(g -> target.id().toString().equals(g.get("targetId")))
                    .singleElement()
                    .satisfies(g -> assertThat(g.get("reportCount")).isEqualTo(2));
        }

        @Test
        @DisplayName("신고자 신원은 응답에 없다 — 피신고자에게도 방장에게도 공개하지 않는다")
        void reporter_identity_is_never_exposed() throws Exception {
            Member op = operator("anon");
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            UUID reportId = insertReport(reporter.id(), target.id());

            MvcResult res = getAuth("/api/v1/admin/reports/" + reportId, op.token());
            String body = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(body).doesNotContain(reporter.id().toString());
        }

        @Test
        @DisplayName("스냅샷 열람은 별도 action 으로 남긴다 — 개인정보 열람이기 때문이다")
        void snapshot_view_is_audited_separately() throws Exception {
            Member op = operator("snapshot");
            UUID reportId = insertReport(member(uniq("r")).id(), member(uniq("t")).id());

            getAuth("/api/v1/admin/reports/" + reportId, op.token());

            assertThat(auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(op.id()))
                    .anyMatch(l -> l.getAction() == AdminAction.SNAPSHOT_VIEW);
        }

        @Test
        @DisplayName("종결하면 상태가 바뀌고 다시 종결하면 409 REVIEW_ALREADY_RESOLVED")
        void resolve_is_once() throws Exception {
            Member op = operator("resolve");
            UUID reportId = insertReport(member(uniq("r")).id(), member(uniq("t")).id());

            MvcResult first = postAuth("/api/v1/admin/reports/" + reportId + "/resolve",
                    op.token(), Map.of("resolution", "NO_ACTION"));
            assertThat(first.getResponse().getStatus()).isEqualTo(200);

            expectError(postAuth("/api/v1/admin/reports/" + reportId + "/resolve",
                    op.token(), Map.of("resolution", "NO_ACTION")), 409, "REVIEW_ALREADY_RESOLVED");
        }

        @Test
        @DisplayName("종결해도 신고자의 개인 차단은 유지된다 — 차단은 제재가 아니라 개인 선택이다")
        void resolving_keeps_personal_block() throws Exception {
            Member op = operator("keepblock");
            Member reporter = member(uniq("r"));
            Member target = member(uniq("t"));
            UUID reportId = insertReport(reporter.id(), target.id());
            jdbcTemplate.update("INSERT INTO blacklist_users (owner_id, blocked_user_id) VALUES (?, ?)",
                    bytes(reporter.id()), bytes(target.id()));

            postAuth("/api/v1/admin/reports/" + reportId + "/resolve",
                    op.token(), Map.of("resolution", "NO_ACTION"));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM blacklist_users WHERE owner_id=? AND blocked_user_id=?",
                    Integer.class, bytes(reporter.id()), bytes(target.id()))).isEqualTo(1);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("이상탐지 · 장애 구제 · 운영 공지")
    class Others {

        @Test
        @DisplayName("이상탐지 신호는 조회만 되고 그것만으로 제재하지 않는다")
        void anomaly_is_review_only() throws Exception {
            Member op = operator("anomaly");
            Member target = member(uniq("t"));
            jdbcTemplate.update("INSERT INTO anomaly_signals " +
                            "(id, signal_type, target_user_id, score, detected_at) " +
                            "VALUES (?, 'REPORT_ABUSE', ?, 80, NOW(3))",
                    bytes(UUID.randomUUID()), bytes(target.id()));

            MvcResult res = getAuth("/api/v1/admin/anomalies", op.token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(res, "$.data.items")).isNotEmpty();

            assertThat(sanctionRepository.findByUserIdOrderByStartsAtDesc(target.id()))
                    .as("탐지만으로는 제재하지 않는다").isEmpty();
            assertThat(userRepository.findById(target.id()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("장애 구제는 성공 처리가 아니라 분모에서 제외하는 중립 처리다")
        void outage_relief_is_neutral() throws Exception {
            Member op = operator("relief");

            MvcResult res = confirmAndPost("/api/v1/admin/outage-relief", op.token(), Map.of(
                    "periodStart", "2026-08-30T00:00:00Z",
                    "periodEnd", "2026-08-30T06:00:00Z",
                    "scope", "ALL"));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.scope")).isEqualTo("ALL");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outage_reliefs", Integer.class)).isPositive();
        }

        @Test
        @DisplayName("운영 공지는 필수(A) 알림으로 나간다")
        void notice_is_required_category() throws Exception {
            Member op = operator("opsnotice");
            Member reader = member(uniq("reader"));

            MvcResult res = confirmAndPost("/api/v1/admin/notices", op.token(), Map.of(
                    "title", "점검 안내",
                    "body", "02:00~03:00 점검이 있어요."));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            assertThat(notificationRepository.findInbox(reader.id(), null, null, Limit.unlimited()))
                    .anyMatch(n -> NotificationType.TERMS_UPDATED.name().equals(n.getType())
                            || "A".equals(n.getCategory()));
        }

        @Test
        @DisplayName("유저 통합 뷰는 자동·직권 제재를 별개 트랙으로 내린다")
        void user_view_separates_tracks() throws Exception {
            Member op = operator("userview");
            Member target = member(uniq("t"));
            confirmAndPost("/api/v1/admin/users/" + target.id() + "/sanctions",
                    op.token(), sanctionBody("LOCK"));

            MvcResult res = getAuth("/api/v1/admin/users/" + target.id(), op.token());
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.accountStatus")).isEqualTo("SUSPENDED");
            assertThat((List<?>) read(res, "$.data.adminSanctions")).hasSize(1);
            assertThat((List<?>) read(res, "$.data.autoSanctions")).isEmpty();
        }
    }
}
