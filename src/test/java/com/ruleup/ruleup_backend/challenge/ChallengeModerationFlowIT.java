package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationRetryService;
import com.ruleup.ruleup_backend.challenge.moderation.ChallengeModerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 챌린지 제목·설명·이미지 비동기 사후 심사 계약 테스트 — 기능 스펙 6-1 #4·API 명세 기준.
 *
 *  - 심사 대상: 사용자 직접 수정분만(서버 draftId 원본 대조 판정 — PR3). AI 원본은 EXEMPT 로 심사 없음.
 *  - 제목+설명은 한 세트로 1회 심사, 결과는 항목별. 심사 중·거부 시 기능 제한 없음(모집 차단 폐기).
 *  - 대체 표시: 타인 화면 = AI 임시 제목·빈 설명·기본 이미지 / 방장 본인 화면 = 입력 원본.
 *  - 거부 플로우: 수정 요청 알림 + 대체 표시 유지. 이미지 거부는 이미지 삭제 + 방장 알림.
 *  - 반복 거부 제한: 1시간 내 3회 거부 → 1시간 수정 잠금(moderation_locked_until).
 *  - 구 1시간 수정창·미수정 하드 삭제 플로우는 폐기 — 심사 실패(AI 미가용)는 IN_REVIEW 유지 + 재시도 배치.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeModerationFlowIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChallengeModerationService moderationService;
    @Autowired ChallengeModerationRetryService retryService;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static final long TEMPLATE = 9301L;
    private static boolean fixtures;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        if (!fixtures) {
            insertAutoTemplate(TEMPLATE, "헬스장 가기", "퇴근 후 운동 습관", "EXERCISE",
                    "{\"duration_min\":{\"default\":60,\"unit\":\"min\",\"min\":10,\"max\":480}}",
                    "GPS_PRESENCE", "[\"ACCESS_FINE_LOCATION\"]");
            fixtures = true;
        }
    }

    /** by-template 초안 → (선택) 제목·설명 수정 → 생성. challengeId 반환. */
    private String createWith(String token, String title, String description) throws Exception {
        MvcResult draft = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", TEMPLATE));
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draft, "$.data.draftId"));
        body.put("title", title != null ? title : read(draft, "$.data.draft.title"));
        body.put("description", description != null ? description : read(draft, "$.data.draft.description"));
        body.put("category", (String) read(draft, "$.data.draft.category"));
        body.put("mode", "SOLO");
        body.put("capacity", 50);
        body.put("minTier", "BRONZE");
        body.put("period", Map.of(
                "start", (String) read(draft, "$.data.draft.period.start"),
                "end", (String) read(draft, "$.data.draft.period.end")));
        body.put("params", List.of());
        body.put("verification", Map.of("type", "AUTO", "method", "GPS_PRESENCE"));
        body.put("penalties", Map.of("watcher", false));

        MvcResult res = mvc.perform(post("/api/v1/challenges")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(OM.writeValueAsString(body)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return read(res, "$.data.challengeId");
    }

    private Map<String, Object> moderationRow(String challengeId) {
        return jdbcTemplate.queryForMap(
                "SELECT title, ai_title, description, image_url, moderation_title, moderation_description, " +
                        " moderation_image, moderation_locked_until, moderation_reject_count " +
                        "FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", challengeId);
    }

    private int notificationCount(UUID userId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
                Integer.class, bytes(userId), type);
    }

    /** 비동기 심사(AFTER_COMMIT·@Async) 완료 폴링 — 최대 5초. */
    private Map<String, Object> awaitTitleDecided(String challengeId) throws Exception {
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = moderationRow(challengeId);
            if (!"IN_REVIEW".equals(row.get("moderation_title"))) return row;
            Thread.sleep(100);
        }
        return moderationRow(challengeId);
    }

    // =====================================================================
    @Nested
    @DisplayName("텍스트 세트 1회 심사 — 항목별 결과")
    class TextModeration {

        @Test
        @DisplayName("건전한 제목 수정 → 생성 직후 비동기 심사가 APPROVED 로 확정(설명은 EXEMPT 유지)")
        void cleanEditApproved() throws Exception {
            String token = memberToken(uniq("mod-ok"));
            String id = createWith(token, "내가 고친 건전한 제목", null);

            Map<String, Object> row = awaitTitleDecided(id);
            assertThat(row.get("moderation_title")).isEqualTo("APPROVED");
            assertThat(row.get("moderation_description")).isEqualTo("EXEMPT");
        }

        @Test
        @DisplayName("부적절 제목(비속어) → REJECTED + 수정 요청 알림 + 거부 카운트 1")
        void rejectedWithNotification() throws Exception {
            Member m = member(uniq("mod-rej"));
            String id = createWith(m.token(), "비속어 섞인 제목", null);

            Map<String, Object> row = awaitTitleDecided(id);
            assertThat(row.get("moderation_title")).isEqualTo("REJECTED");
            assertThat(((Number) row.get("moderation_reject_count")).intValue()).isEqualTo(1);
            assertThat(notificationCount(m.id(), "MODERATION_REJECTED")).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("심사 중·거부 시 타인 화면 = AI 임시 제목·빈 설명 / 방장 화면 = 입력 원본")
        void substituteDisplayForOthers() throws Exception {
            Member owner = member(uniq("mod-sub-o"));
            String id = createWith(owner.token(), "비속어 섞인 제목", "비속어 설명도 고침");
            awaitTitleDecided(id);
            // 생성 기본값은 솔로라 타인에게는 존재가 숨겨진다(404). 여기서 보려는 것은 "대체 표시"이므로
            // 타인이 볼 수 있는 공개 그룹 방으로 바꾼 뒤 확인한다.
            jdbc().update("UPDATE challenges SET mode = 'GROUP', visibility = 'PUBLIC' " +
                    "WHERE id = UNHEX(REPLACE(?, '-', ''))", id);

            // 방장 본인: 입력 원본 그대로
            MvcResult mine = getAuth("/api/v1/challenges/" + id, owner.token());
            assertThat(mine.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(mine, "$.data.title")).isEqualTo("비속어 섞인 제목");

            // 타인: AI 임시 제목 + 빈 설명 (기능 제한은 없다 — 404 아님)
            Member other = member(uniq("mod-sub-x"));
            MvcResult theirs = getAuth("/api/v1/challenges/" + id, other.token());
            assertThat(theirs.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(theirs, "$.data.title")).isEqualTo("헬스장 가기");
            assertThat((Object) read(theirs, "$.data.description")).isNull();
        }

        @Test
        @DisplayName("1시간 내 3회 거부 → 1시간 수정 잠금(moderation_locked_until 설정)")
        void threeRejectionsLock() throws Exception {
            Member m = member(uniq("mod-lock"));
            String id = createWith(m.token(), "비속어 섞인 제목", null);
            awaitTitleDecided(id);   // 1회차 거부

            for (int round = 2; round <= 3; round++) {
                // 수정 재심사 상황 재현: 다시 IN_REVIEW 로 두고 심사
                jdbcTemplate.update("UPDATE challenges SET moderation_title = 'IN_REVIEW' " +
                        "WHERE id = UNHEX(REPLACE(?, '-', ''))", id);
                moderationService.moderate(UUID.fromString(id));
            }

            Map<String, Object> row = moderationRow(id);
            assertThat(((Number) row.get("moderation_reject_count")).intValue()).isEqualTo(3);
            assertThat(row.get("moderation_locked_until")).isNotNull();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("이미지 심사")
    class ImageModeration {

        @Test
        @DisplayName("이미지 거부 → 이미지 삭제(기본 이미지로) + 방장 알림")
        void imageRejectedDeleted() throws Exception {
            Member m = member(uniq("mod-img"));
            String id = createWith(m.token(), null, null);
            // 이미지 재심사 상황 재현(업로드 URL 은 랜덤 파일명이라 fake 거부 표식을 직접 주입)
            jdbcTemplate.update("UPDATE challenges SET image_url = '/uploads/reject-cover.png', " +
                    "moderation_image = 'IN_REVIEW' WHERE id = UNHEX(REPLACE(?, '-', ''))", id);

            moderationService.moderate(UUID.fromString(id));

            Map<String, Object> row = moderationRow(id);
            assertThat(row.get("moderation_image")).isEqualTo("REJECTED");
            assertThat(row.get("image_url")).isNull();
            assertThat(notificationCount(m.id(), "CHALLENGE_IMAGE_REMOVED")).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("이미지 통과 → APPROVED, 이미지 유지")
        void imageApproved() throws Exception {
            Member m = member(uniq("mod-img-ok"));
            String id = createWith(m.token(), null, null);
            jdbcTemplate.update("UPDATE challenges SET image_url = '/uploads/clean-cover.png', " +
                    "moderation_image = 'IN_REVIEW' WHERE id = UNHEX(REPLACE(?, '-', ''))", id);

            moderationService.moderate(UUID.fromString(id));

            Map<String, Object> row = moderationRow(id);
            assertThat(row.get("moderation_image")).isEqualTo("APPROVED");
            assertThat(row.get("image_url")).isEqualTo("/uploads/clean-cover.png");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("심사 실패 복구 — 재시도 배치")
    class RetryBatch {

        @Test
        @DisplayName("이벤트 유실 등으로 IN_REVIEW 로 지체된 건을 배치가 재심사해 수렴시킨다")
        void retryStalled() throws Exception {
            String token = memberToken(uniq("mod-retry"));
            String id = createWith(token, null, null);
            // 심사 이벤트가 유실된 상황 재현: 수정분이 IN_REVIEW 인 채 오래 지체
            jdbcTemplate.update("UPDATE challenges SET moderation_title = 'IN_REVIEW', title = '고친 제목', " +
                    "updated_at = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = UNHEX(REPLACE(?, '-', ''))", id);

            retryService.retryStalledModeration();

            assertThat(moderationRow(id).get("moderation_title")).isEqualTo("APPROVED");
        }
    }
}
