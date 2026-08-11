package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
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
 * 챌린지 설정 조회(방장 전용)·수정 계약 테스트 — API 명세 「챌린지 설정 조회」·「챌린지 수정」 기준.
 *
 *  - GET /settings: config 원본(심사 대체 미적용) + editableFields(서버 계산) + version + moderation.
 *  - PATCH: JSON Merge Patch 유사 — 미포함 필드 미변경, null 은 imageUrl 만 유효(기본 이미지 되돌리기).
 *    수정 잠금(시작 전+혼자=카테고리 제외 전부 / 그 외=제목·설명·정원·이미지), version 낙관 잠금,
 *    제목·설명 수정 시 재심사, 반복 거부 잠금 중 429 MODERATION_LOCKED.
 *  - 참여 인원이 변하는 경로(가입)도 version 을 증가시킨다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeSettingsPatchIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    private static final long TEMPLATE = 9401L;
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

    /** GROUP·PUBLIC 챌린지 생성(미수정 초안 그대로) → challengeId. */
    private String createGroupChallenge(String token) throws Exception {
        MvcResult draft = postJsonAuth("/api/v1/challenges/recommendation/by-template", token,
                Map.of("templateId", TEMPLATE));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", (String) read(draft, "$.data.draftId"));
        body.put("title", (String) read(draft, "$.data.draft.title"));
        body.put("description", (String) read(draft, "$.data.draft.description"));
        body.put("category", "EXERCISE");
        body.put("mode", "GROUP");
        body.put("visibility", "PUBLIC");
        body.put("capacity", 50);
        body.put("minTier", "BRONZE");
        body.put("period", Map.of(
                "start", (String) read(draft, "$.data.draft.period.start"),
                "end", (String) read(draft, "$.data.draft.period.end")));
        body.put("weeklyCount", (Integer) read(draft, "$.data.draft.weeklyCount"));
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

    private MvcResult settings(String token, String challengeId) throws Exception {
        return getAuth("/api/v1/challenges/" + challengeId + "/settings", token);
    }

    private int currentVersion(String token, String challengeId) throws Exception {
        MvcResult res = settings(token, challengeId);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.version");
    }

    /** 다른 참여자 1명 합류 상황 재현(직접 insert — 가입 API 계약은 다음 스택). */
    private void addMember(String challengeId, UUID userId) {
        insertActiveMembership(UUID.fromString(challengeId), userId, "MEMBER");
        jdbcTemplate.update("UPDATE challenges SET participant_count = participant_count + 1, " +
                "version = version + 1 WHERE id = UNHEX(REPLACE(?, '-', ''))", challengeId);
    }

    // =====================================================================
    @Nested
    @DisplayName("GET /settings — 방장 전용 설정 조회")
    class SettingsApi {

        @Test
        @DisplayName("방장 아님 403 / 없는 챌린지 404")
        void guards() throws Exception {
            Member owner = member(uniq("set-own"));
            String id = createGroupChallenge(owner.token());

            String other = memberToken(uniq("set-oth"));
            expectError(settings(other, id), 403, "NOT_CHALLENGE_OWNER");
            expectError(settings(owner.token(), UUID.randomUUID().toString()), 404, "CHALLENGE_NOT_FOUND");
        }

        @Test
        @DisplayName("시작 전 + 방장 혼자: editableFields = 카테고리 제외 전부(minTier·period·verification 포함)")
        void fullEditableBeforeStartAlone() throws Exception {
            Member owner = member(uniq("set-full"));
            String id = createGroupChallenge(owner.token());

            MvcResult res = settings(owner.token(), id);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            // config 원본
            assertThat((String) read(res, "$.data.config.title")).isEqualTo("헬스장 가기");
            assertThat((String) read(res, "$.data.config.category")).isEqualTo("EXERCISE");
            assertThat((String) read(res, "$.data.config.mode")).isEqualTo("GROUP");
            assertThat((String) read(res, "$.data.config.visibility")).isEqualTo("PUBLIC");
            assertThat((Integer) read(res, "$.data.config.capacity")).isEqualTo(50);
            assertThat((String) read(res, "$.data.config.minTier")).isEqualTo("BRONZE");
            assertThat((String) read(res, "$.data.config.verification.type")).isEqualTo("AUTO");
            assertThat((Boolean) read(res, "$.data.config.penalties.score")).isTrue();
            assertThat((Boolean) read(res, "$.data.config.penalties.groupShare")).isTrue();
            assertThat(res.getResponse().getContentAsString()).doesNotContain("\"repeatDays\"");
            assertThat((Integer) read(res, "$.data.config.weeklyCount")).isEqualTo(7);
            // 목표값 스펙 복원(기본값 채움)
            assertThat((String) read(res, "$.data.config.params[0].key")).isEqualTo("duration_min");

            List<String> editable = read(res, "$.data.editableFields");
            assertThat(editable).contains("title", "description", "capacity", "imageUrl",
                    "minTier", "period", "weeklyCount", "params", "verification", "mode", "visibility");
            assertThat(editable).doesNotContain("category");

            assertThat((Integer) read(res, "$.data.version")).isEqualTo(0);
            assertThat((String) read(res, "$.data.moderation.title")).isEqualTo("EXEMPT");
            assertThat((String) read(res, "$.data.moderation.image")).isEqualTo("NONE");
        }

        @Test
        @DisplayName("다른 참여자가 생기면 editableFields = 제목·설명·정원·이미지만")
        void limitedWhenMemberJoined() throws Exception {
            Member owner = member(uniq("set-lim"));
            String id = createGroupChallenge(owner.token());
            Member other = member(uniq("set-lim2"));
            addMember(id, other.id());

            MvcResult res = settings(owner.token(), id);
            List<String> editable = read(res, "$.data.editableFields");
            assertThat(editable).containsExactlyInAnyOrder("title", "description", "capacity", "imageUrl");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("PATCH — merge-patch·잠금·낙관 잠금")
    class PatchApi {

        @Test
        @DisplayName("version 불일치 → 409 VERSION_CONFLICT")
        void versionConflict() throws Exception {
            Member owner = member(uniq("pat-ver"));
            String id = createGroupChallenge(owner.token());
            expectError(patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                            Map.of("version", 99, "capacity", 100)),
                    409, "VERSION_CONFLICT");
        }

        @Test
        @DisplayName("정원 수정 성공: updated 반영·version 증가·미포함 필드는 미변경")
        void capacityPatch() throws Exception {
            Member owner = member(uniq("pat-cap"));
            String id = createGroupChallenge(owner.token());
            int v = currentVersion(owner.token(), id);

            MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                    Map.of("version", v, "capacity", 100));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.updated.capacity")).isEqualTo(100);
            assertThat((Object) read(res, "$.data.moderation")).isNull();

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, capacity, version FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", id);
            assertThat(row.get("title")).isEqualTo("헬스장 가기");   // 미포함 필드 미변경
            assertThat(((Number) row.get("capacity")).intValue()).isEqualTo(100);
            assertThat(((Number) row.get("version")).intValue()).isEqualTo(v + 1);
        }

        @Test
        @DisplayName("제목 수정 → 재심사(IN_REVIEW) 응답 + 잠금 무관 필드라 시작 후에도 허용")
        void titlePatchTriggersReview() throws Exception {
            Member owner = member(uniq("pat-title"));
            String id = createGroupChallenge(owner.token());
            jdbcTemplate.update("UPDATE challenges SET status = 'ACTIVE' " +
                    "WHERE id = UNHEX(REPLACE(?, '-', ''))", id);   // 시작 후에도 제목은 수정 가능
            int v = currentVersion(owner.token(), id);

            MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                    Map.of("version", v, "title", "내가 고친 제목"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.moderation.title")).isEqualTo("IN_REVIEW");

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT title, moderation_title FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", id);
            assertThat(row.get("title")).isEqualTo("내가 고친 제목");
        }

        @Test
        @DisplayName("null 전송은 imageUrl 만 허용 — title=null 은 400 INVALID_FIELD_VALUE")
        void nullOnlyForImage() throws Exception {
            Member owner = member(uniq("pat-null"));
            String id = createGroupChallenge(owner.token());
            int v = currentVersion(owner.token(), id);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", v);
            body.put("title", null);
            expectError(patchJsonAuth("/api/v1/challenges/" + id, owner.token(), body),
                    400, "INVALID_FIELD_VALUE");

            // imageUrl=null 은 "기본 이미지로 되돌리기"
            Map<String, Object> imageBody = new LinkedHashMap<>();
            imageBody.put("version", v);
            imageBody.put("imageUrl", null);
            MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, owner.token(), imageBody);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            String imageUrl = jdbcTemplate.queryForObject(
                    "SELECT image_url FROM challenges WHERE id = UNHEX(REPLACE(?, '-', ''))", String.class, id);
            assertThat(imageUrl).isNull();
        }

        @Test
        @DisplayName("다른 참여자가 있으면 방 성격 항목(minTier 등)은 409 CHALLENGE_NOT_EDITABLE")
        void lockedFieldsRejected() throws Exception {
            Member owner = member(uniq("pat-lock"));
            String id = createGroupChallenge(owner.token());
            Member other = member(uniq("pat-lock2"));
            addMember(id, other.id());
            int v = currentVersion(owner.token(), id);

            expectError(patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                            Map.of("version", v, "minTier", "BRONZE")),
                    409, "CHALLENGE_NOT_EDITABLE");
        }

        @Test
        @DisplayName("정원은 현재 참여 인원 미만으로 축소 불가 → 400 CAPACITY_BELOW_CURRENT")
        void capacityBelowCurrent() throws Exception {
            Member owner = member(uniq("pat-below"));
            String id = createGroupChallenge(owner.token());
            Member other = member(uniq("pat-below2"));
            addMember(id, other.id());   // 참여 2명
            int v = currentVersion(owner.token(), id);

            expectError(patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                            Map.of("version", v, "capacity", 1)),
                    400, "CAPACITY_BELOW_CURRENT");
        }

        @Test
        @DisplayName("반복 거부 수정 잠금 중 제목 수정 → 429 MODERATION_LOCKED")
        void moderationLocked() throws Exception {
            Member owner = member(uniq("pat-mlock"));
            String id = createGroupChallenge(owner.token());
            jdbcTemplate.update("UPDATE challenges SET moderation_locked_until = DATE_ADD(NOW(), INTERVAL 30 MINUTE) " +
                    "WHERE id = UNHEX(REPLACE(?, '-', ''))", id);
            int v = currentVersion(owner.token(), id);

            expectError(patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                            Map.of("version", v, "title", "다시 고친 제목")),
                    429, "MODERATION_LOCKED");
        }

        @Test
        @DisplayName("GROUP→SOLO 전환(시작 전+혼자): 파생 필드 서버 정규화(visibility null·rankingVisible true·groupShare OFF)")
        void modeNormalization() throws Exception {
            Member owner = member(uniq("pat-mode"));
            String id = createGroupChallenge(owner.token());
            int v = currentVersion(owner.token(), id);

            MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                    Map.of("version", v, "mode", "SOLO"));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            MvcResult after = settings(owner.token(), id);
            assertThat((String) read(after, "$.data.config.mode")).isEqualTo("SOLO");
            assertThat((Object) read(after, "$.data.config.visibility")).isNull();
            assertThat((Boolean) read(after, "$.data.config.rankingVisible")).isTrue();
            assertThat((Boolean) read(after, "$.data.config.penalties.groupShare")).isFalse();
        }

        @Test
        @DisplayName("시작 전+혼자일 때 weeklyCount 수정")
        void weeklyCountPatch() throws Exception {
            Member owner = member(uniq("pat-repeat"));
            String id = createGroupChallenge(owner.token());
            int v = currentVersion(owner.token(), id);

            MvcResult res = patchJsonAuth("/api/v1/challenges/" + id, owner.token(),
                    Map.of("version", v, "weeklyCount", 3));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Integer) read(res, "$.data.updated.weeklyCount")).isEqualTo(3);

            MvcResult after = settings(owner.token(), id);
            assertThat(after.getResponse().getContentAsString()).doesNotContain("\"repeatDays\"");
            assertThat((Integer) read(after, "$.data.config.weeklyCount")).isEqualTo(3);
        }
    }
}
