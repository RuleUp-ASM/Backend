package com.ruleup.ruleup_backend.inquiry;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.repository.AdminAuditLogRepository;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.notification.NotificationRepository;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CS 문의 — 앱 운영 정책 § 5, 백오피스 공통 5-2-1 B.
 *
 * <p>안드로이드에서 접수해 <b>운영자 콘솔로 흘러가는 한 경로</b>를 처음부터 끝까지 본다. 지키는
 * 계약은 넷이다.
 * <ol>
 *   <li>입력은 <b>카테고리 1개 + 본문</b> 2단계이며 본문은 10~1,000자다</li>
 *   <li><b>계정이 잠겨도 접수된다</b> — 제재 재검토가 이 채널로 들어온다</li>
 *   <li><b>답변 등록이 곧 종결</b>이고 재등록은 409 다</li>
 *   <li>분류 변경 사실은 <b>유저에게 노출되지 않는다</b></li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InquiryFlowIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AdminAuditLogRepository auditLogRepository;

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

    private Member operator(String tag) throws Exception {
        Member m = member(uniq(tag));
        jdbcTemplate.update("UPDATE users SET role = 'OPERATOR' WHERE id = ?", bytes(m.id()));
        return m;
    }

    private Map<String, Object> body(String category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("body", "판정이 실패로 나왔는데 이유를 모르겠어요. 확인 부탁드립니다.");
        body.put("appVersion", "1.2.0");
        body.put("osVersion", "Android 15");
        body.put("deviceModel", "SM-S928N");
        return body;
    }

    private MvcResult postAuth(String url, String token, Map<String, Object> body) throws Exception {
        var req = post(url).header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body));
        return mvc.perform(req).andReturn();
    }

    /** 접수하고 문의 id 를 돌려준다. */
    private String submit(Member user, String category) throws Exception {
        MvcResult res = postAuth("/api/v1/inquiries", user.token(), body(category));
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return read(res, "$.data.inquiryId");
    }

    // =====================================================================
    @Nested
    @DisplayName("접수 — 카테고리 1개 + 본문의 2단계")
    class Submit {

        @Test
        @DisplayName("접수하면 접수번호와 RECEIVED 상태를 돌려준다")
        void creates_inquiry() throws Exception {
            Member user = member(uniq("cs"));

            MvcResult res = postAuth("/api/v1/inquiries", user.token(), body("VERIFICATION"));

            assertThat(res.getResponse().getStatus()).isEqualTo(201);
            assertThat((String) read(res, "$.data.inquiryId")).isNotBlank();
            assertThat((String) read(res, "$.data.status")).isEqualTo("RECEIVED");
        }

        @Test
        @DisplayName("본문이 10자 미만이면 400 INQUIRY_BODY_LENGTH")
        void body_too_short() throws Exception {
            Member user = member(uniq("short"));
            Map<String, Object> body = body("ERROR_ETC");
            body.put("body", "짧아요");

            expectError(postAuth("/api/v1/inquiries", user.token(), body),
                    400, "INQUIRY_BODY_LENGTH");
        }

        @Test
        @DisplayName("이미지는 3장까지 — 넘으면 400 INQUIRY_IMAGE_LIMIT")
        void image_limit() throws Exception {
            Member user = member(uniq("img"));
            Map<String, Object> body = body("ERROR_ETC");
            body.put("imageUrls", List.of("/files/a.jpg", "/files/b.jpg", "/files/c.jpg", "/files/d.jpg"));

            expectError(postAuth("/api/v1/inquiries", user.token(), body),
                    400, "INQUIRY_IMAGE_LIMIT");
        }

        @Test
        @DisplayName("하루 3건까지 — 네 번째는 429 INQUIRY_DAILY_LIMIT")
        void daily_limit() throws Exception {
            Member user = member(uniq("limit"));
            for (int i = 0; i < 3; i++) submit(user, "ERROR_ETC");

            expectError(postAuth("/api/v1/inquiries", user.token(), body("ERROR_ETC")),
                    429, "INQUIRY_DAILY_LIMIT");
        }

        @Test
        @DisplayName("계정이 잠겨도 접수된다 — 제재 재검토가 이 채널로 들어온다")
        void locked_account_can_submit() throws Exception {
            Member user = member(uniq("locked"));
            lock(user.id());

            // 같은 계정의 다른 쓰기는 잠금에 막힌다 — 화이트리스트가 CS 경로에만 열려 있음을 함께 본다.
            MvcResult blocked = postAuth("/api/v1/reports", user.token(),
                    Map.of("targetType", "USER", "targetUserId", UUID.randomUUID().toString(),
                            "reason", "INAPPROPRIATE"));
            assertThat(blocked.getResponse().getStatus())
                    .as("잠금 중 일반 쓰기는 막힌다").isEqualTo(403);

            assertThat(postAuth("/api/v1/inquiries", user.token(), body("REPORT_SANCTION"))
                    .getResponse().getStatus()).isEqualTo(201);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("열람 — 상세는 열람 전용이고 남의 문의는 없는 것과 같다")
    class Read {

        @Test
        @DisplayName("내 문의 내역과 상세를 볼 수 있다")
        void list_and_detail() throws Exception {
            Member user = member(uniq("read"));
            String inquiryId = submit(user, "CHALLENGE_GROUP");

            MvcResult list = getAuth("/api/v1/inquiries", user.token());
            assertThat((List<?>) read(list, "$.data.items")).hasSize(1);

            MvcResult detail = getAuth("/api/v1/inquiries/" + inquiryId, user.token());
            assertThat((String) read(detail, "$.data.status")).isEqualTo("RECEIVED");
            assertThat((String) read(detail, "$.data.answerText")).isNull();
        }

        @Test
        @DisplayName("영구 정지돼도 내 문의 내역과 상세는 열린다 — 접수만 되고 답변을 못 보면 재검토 채널이 반쪽이다")
        void banned_account_can_read_own_inquiries() throws Exception {
            Member user = member(uniq("banned"));
            String inquiryId = submit(user, "REPORT_SANCTION");
            ban(user.id());

            // BAN 은 조회까지 막으므로 LOCK 의 열람 규칙에 기댈 수 없다 — 화이트리스트가 따로 열어야 한다.
            expectError(getAuth("/api/v1/users/me", user.token()), 403, "ACCOUNT_BANNED");

            MvcResult list = getAuth("/api/v1/inquiries", user.token());
            assertThat(list.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(list, "$.data.items")).hasSize(1);
            assertThat(getAuth("/api/v1/inquiries/" + inquiryId, user.token()).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("남의 문의는 404 — 소유자가 아니면 없는 것과 같다")
        void other_users_inquiry_is_404() throws Exception {
            Member owner = member(uniq("owner"));
            Member other = member(uniq("other"));
            String inquiryId = submit(owner, "ACCOUNT_LOGIN");

            expectError(getAuth("/api/v1/inquiries/" + inquiryId, other.token()),
                    404, "INQUIRY_NOT_FOUND");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("운영자 처리 — 답변 등록이 곧 종결")
    class Operator {

        @Test
        @DisplayName("큐에서 보이고 상세 열람은 개인정보 열람으로 따로 기록된다")
        void queue_and_audited_detail() throws Exception {
            Member op = operator("csop");
            Member user = member(uniq("q"));
            String inquiryId = submit(user, "VERIFICATION");

            MvcResult queue = getAuth("/api/v1/admin/inquiries?status=RECEIVED", op.token());
            assertThat(queue.getResponse().getStatus()).isEqualTo(200);
            assertThat(((Number) read(queue, "$.data.totalCount")).longValue()).isPositive();

            MvcResult detail = getAuth("/api/v1/admin/inquiries/" + inquiryId, op.token());
            assertThat((String) read(detail, "$.data.appVersion")).isEqualTo("1.2.0");
            assertThat((String) read(detail, "$.data.accountStatus")).isEqualTo("ACTIVE");

            assertThat(auditLogRepository.findByOperatorIdOrderByOccurredAtDesc(op.id()))
                    .as("상세 열람은 목록 조회와 다른 action 이다")
                    .anyMatch(l -> l.getAction() == AdminAction.INQUIRY_VIEW);
        }

        @Test
        @DisplayName("답변하면 종결되고 알림이 적재된다")
        void answer_closes_and_notifies() throws Exception {
            Member op = operator("answer");
            Member user = member(uniq("a"));
            String inquiryId = submit(user, "VERIFICATION");

            MvcResult res = postAuth("/api/v1/admin/inquiries/" + inquiryId + "/answer",
                    op.token(), Map.of("answerText", "판정 로그를 확인했고 정상 처리됐습니다."));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.status")).isEqualTo("ANSWERED");

            // 본문을 알림에 싣지 않는다 — 잠금화면에 CS 답변 전문이 뜨면 곤란한 사연이 있다.
            assertThat(notificationRepository.findByUserIdOrderByIdDesc(user.id()))
                    .anyMatch(n -> NotificationType.CS_ANSWERED.name().equals(n.getType())
                            && !n.getBody().contains("판정 로그를 확인했고"));

            MvcResult detail = getAuth("/api/v1/inquiries/" + inquiryId, user.token());
            assertThat((String) read(detail, "$.data.answerText"))
                    .isEqualTo("판정 로그를 확인했고 정상 처리됐습니다.");
        }

        @Test
        @DisplayName("답변 재등록은 409 ALREADY_ANSWERED — 재문의 경로가 없다")
        void answer_is_once() throws Exception {
            Member op = operator("once");
            Member user = member(uniq("o"));
            String inquiryId = submit(user, "ERROR_ETC");

            postAuth("/api/v1/admin/inquiries/" + inquiryId + "/answer",
                    op.token(), Map.of("answerText", "확인했습니다."));

            expectError(postAuth("/api/v1/admin/inquiries/" + inquiryId + "/answer",
                    op.token(), Map.of("answerText", "다시 확인했습니다.")), 409, "ALREADY_ANSWERED");
        }

        @Test
        @DisplayName("분류를 바꿔도 유저에게는 알리지 않고 원본 분류는 남는다")
        void reclassify_is_invisible_to_user() throws Exception {
            Member op = operator("recat");
            Member user = member(uniq("c"));
            String inquiryId = submit(user, "ERROR_ETC");

            MvcResult res = mvc.perform(patch("/api/v1/admin/inquiries/" + inquiryId + "/category")
                    .header("Authorization", "Bearer " + op.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(Map.of("category", "VERIFICATION")))).andReturn();

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.category")).isEqualTo("VERIFICATION");
            assertThat((String) read(res, "$.data.originCategory"))
                    .as("지표는 유저가 처음 고른 분류로 센다").isEqualTo("ERROR_ETC");

            // 유저 응답에는 변경 흔적이 없다 — 현재 분류만 보이고 원본 분류 필드가 아예 없다.
            String userView = getAuth("/api/v1/inquiries/" + inquiryId, user.token())
                    .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(userView).doesNotContain("originCategory");
        }

        @Test
        @DisplayName("일반 회원은 문의 큐에 접근할 수 없다")
        void member_cannot_read_queue() throws Exception {
            Member normal = member(uniq("nope"));
            expectError(getAuth("/api/v1/admin/inquiries", normal.token()), 403, "ADMIN_FORBIDDEN");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("대시보드 — 지표와 가드레일은 층이 다르다")
    class Dashboard {

        @Test
        @DisplayName("요약에 CS·신고 지표와 가드레일이 함께 실린다")
        void summary_contains_metrics_and_guardrails() throws Exception {
            Member op = operator("dash");
            Member user = member(uniq("d"));
            submit(user, "VERIFICATION");

            MvcResult res = mvc.perform(get("/api/v1/admin/dashboard/summary?range=30d")
                    .header("Authorization", "Bearer " + op.token())).andReturn();

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.range")).isEqualTo("30d");
            assertThat(((Number) read(res, "$.data.inquiries.open")).longValue()).isPositive();
            assertThat((List<?>) read(res, "$.data.inquiries.byCategory")).isNotEmpty();

            // 가드레일은 지표가 아니라 0이어야 하는 값이다 — 필드가 없으면 콘솔이 경고를 띄울 수 없다.
            assertThat(((Number) read(res, "$.data.guardrails.discretionaryWithoutSource"))
                    .longValue()).isZero();
        }

        @Test
        @DisplayName("모르는 range 는 400 — 임의의 창을 만들지 않는다")
        void unknown_range_is_rejected() throws Exception {
            Member op = operator("range");
            expectError(mvc.perform(get("/api/v1/admin/dashboard/summary?range=1y")
                    .header("Authorization", "Bearer " + op.token())).andReturn(),
                    400, "INVALID_REQUEST");
        }
    }
}
