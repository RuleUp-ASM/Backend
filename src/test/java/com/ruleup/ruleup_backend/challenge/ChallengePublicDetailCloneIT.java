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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 공개 상세 · 템플릿 복제 계약 테스트 — API 명세 · 공통 테크스펙 §5-4 · 백엔드 §11-4·§11-5.
 *
 * <p>핵심은 <b>존재 은닉</b>이다. 없는 방, 비공개 방의 비멤버, 타인의 솔로 방은 전부 같은 404 여야 한다 —
 * 403 과 404 를 나눠 주면 "그 방은 있다"는 사실이 새기 때문이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengePublicDetailCloneIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private UUID room(UUID owner, String mode, String visibility, String status) {
        UUID id = insertChallenge(owner, "EXERCISE", status, mode);
        jdbcTemplate.update("UPDATE challenges SET visibility = ?, verification_type = 'MANUAL' WHERE id = ?",
                visibility, bytes(id));
        jdbcTemplate.update("INSERT INTO challenge_stats (challenge_id) VALUES (?) " +
                "ON DUPLICATE KEY UPDATE challenge_id = challenge_id", (Object) bytes(id));
        insertActiveMembership(id, owner, "OWNER");
        jdbcTemplate.update("UPDATE challenges SET participant_count = 1 WHERE id = ?", (Object) bytes(id));
        return id;
    }

    private MvcResult detail(String token, UUID challengeId) throws Exception {
        return getAuth("/api/v1/challenges/" + challengeId, token);
    }

    private MvcResult cloneRoom(String token, UUID challengeId) throws Exception {
        return postJsonAuth("/api/v1/challenges/" + challengeId + "/clone", token, Map.of());
    }

    // =====================================================================
    @Nested
    @DisplayName("공개 상세")
    class PublicDetail {

        @Test
        @DisplayName("공개 그룹 방은 조건·통계·입장 자격을 함께 내려준다")
        void publicGroupContract() throws Exception {
            var owner = member(uniq("d-owner"));
            String viewer = memberToken(uniq("d-viewer"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");

            MvcResult res = detail(viewer, id);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.challengeId")).isEqualTo(id.toString());
            assertThat((String) read(res, "$.data.mode")).isEqualTo("GROUP");
            assertThat((String) read(res, "$.data.visibility")).isEqualTo("PUBLIC");
            assertThat((String) read(res, "$.data.status")).isEqualTo("ACTIVE");
            assertThat((String) read(res, "$.data.ownerType")).isEqualTo("USER");
            assertThat((String) read(res, "$.data.owner.userId")).isEqualTo(owner.id().toString());
            assertThat((Integer) read(res, "$.data.participantCount")).isEqualTo(1);
            assertThat((Boolean) read(res, "$.data.isFull")).isFalse();
            assertThat((Integer) read(res, "$.data.period.remainingDays")).isNotNull();
            assertThat((String) read(res, "$.data.gate.myDisplayTier")).isNotBlank();
            assertThat((Boolean) read(res, "$.data.gate.eligible")).isTrue();
            assertThat((String) read(res, "$.data.myRole")).isEqualTo("NONE");
            assertThat((Boolean) read(res, "$.data.cloneable")).isTrue();
            // 방장이 아니면 심사 상태는 내려주지 않는다
            assertThat((Object) read(res, "$.data.moderation")).isNull();
        }

        @Test
        @DisplayName("방장 본인이 보면 심사 상태를 함께 내려준다")
        void ownerSeesModeration() throws Exception {
            var owner = member(uniq("d-mod"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");

            MvcResult res = detail(owner.token(), id);
            assertThat((String) read(res, "$.data.myRole")).isEqualTo("OWNER");
            assertThat((String) read(res, "$.data.moderation.title")).isNotBlank();
        }

        @Test
        @DisplayName("없는 방·비공개 방 비멤버·타인의 솔로 방은 전부 같은 404다")
        void hidesExistence() throws Exception {
            var owner = member(uniq("d-hide-owner"));
            String outsider = memberToken(uniq("d-outsider"));

            UUID privateRoom = room(owner.id(), "GROUP", "PRIVATE", "ACTIVE");
            UUID soloRoom = room(owner.id(), "SOLO", null, "ACTIVE");

            expectError(detail(outsider, UUID.randomUUID()), 404, "CHALLENGE_NOT_FOUND");
            expectError(detail(outsider, privateRoom), 404, "CHALLENGE_NOT_FOUND");
            expectError(detail(outsider, soloRoom), 404, "CHALLENGE_NOT_FOUND");

            // 소유자·멤버는 볼 수 있다
            assertThat(detail(owner.token(), privateRoom).getResponse().getStatus()).isEqualTo(200);
            assertThat(detail(owner.token(), soloRoom).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("들어갈 수 없으면 이유를 미리 알려준다 — 가입 API와 같은 reason enum")
        void previewsJoinBlockReason() throws Exception {
            var owner = member(uniq("d-block-owner"));
            String viewer = memberToken(uniq("d-block-viewer"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");
            jdbcTemplate.update("UPDATE challenges SET min_tier = 'DIAMOND' WHERE id = ?", (Object) bytes(id));

            MvcResult res = detail(viewer, id);
            assertThat((String) read(res, "$.data.gate.minTier")).isEqualTo("DIAMOND");
            assertThat((Boolean) read(res, "$.data.gate.eligible")).isFalse();
            assertThat((String) read(res, "$.data.joinBlockReason")).isEqualTo("TIER_GATE");
        }

        @Test
        @DisplayName("표본이 모자란 방은 완주율·유지율을 내려주지 않는다")
        void statsHiddenWhenSampleShort() throws Exception {
            var owner = member(uniq("d-stats"));
            String viewer = memberToken(uniq("d-stats-viewer"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");

            MvcResult res = detail(viewer, id);
            assertThat((Object) read(res, "$.data.stats.completionRate")).isNull();
            assertThat((Object) read(res, "$.data.stats.retentionRate")).isNull();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("템플릿 복제")
    class Clone {

        @Test
        @DisplayName("공개 그룹 방을 복제하면 원본 설정을 프리필한 초안이 나온다")
        void clonesPublicGroup() throws Exception {
            var owner = member(uniq("c-owner"));
            String cloner = memberToken(uniq("c-cloner"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");

            MvcResult res = cloneRoom(cloner, id);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.draftId")).isNotBlank();
            assertThat((String) read(res, "$.data.sourceChallengeId")).isEqualTo(id.toString());
            assertThat((String) read(res, "$.data.draft.title")).isNotBlank();
            assertThat((String) read(res, "$.data.draft.category")).isEqualTo("EXERCISE");

            // 출처는 서버가 draft 행에 기록한다 — 클라이언트가 지정할 수 없다
            String draftId = read(res, "$.data.draftId");
            String origin = jdbcTemplate.queryForObject(
                    "SELECT origin FROM challenge_drafts WHERE id = UNHEX(REPLACE(?, '-', ''))",
                    String.class, draftId);
            assertThat(origin).isEqualTo("CLONE");
        }

        @Test
        @DisplayName("복제본은 생성 기본값으로 리셋된다 — 솔로·시작일 내일·이미지 미복사")
        void resetsToCreationDefaults() throws Exception {
            var owner = member(uniq("c-reset"));
            String cloner = memberToken(uniq("c-reset-cloner"));
            UUID id = room(owner.id(), "GROUP", "PUBLIC", "ACTIVE");
            jdbcTemplate.update("UPDATE challenges SET image_url = 'https://cdn.example.com/a.jpg', " +
                    "min_tier = 'GOLD', capacity = 7 WHERE id = ?", (Object) bytes(id));

            MvcResult res = cloneRoom(cloner, id);
            assertThat((String) read(res, "$.data.draft.mode")).isEqualTo("SOLO");
            // 날짜 축은 KST다. 시스템 기본 타임존으로 단정하면 UTC로 도는 CI에서 하루 어긋난다.
            assertThat((String) read(res, "$.data.draft.period.start"))
                    .isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                            .plusDays(1).toString());
            // 정원·티어는 생성 기본값으로 리셋, 이미지는 복사하지 않는다(초안 스키마에 이미지 없음)
            assertThat((Integer) read(res, "$.data.draft.capacity")).isEqualTo(50);
            assertThat((String) read(res, "$.data.draft.minTier")).isEqualTo("BRONZE");
        }

        @Test
        @DisplayName("비공개·솔로 방은 복제할 수 없다")
        void privateAndSoloAreNotCloneable() throws Exception {
            var owner = member(uniq("c-deny-owner"));
            String cloner = memberToken(uniq("c-deny"));
            UUID priv = room(owner.id(), "GROUP", "PRIVATE", "ACTIVE");
            UUID solo = room(owner.id(), "SOLO", null, "ACTIVE");

            // 비멤버에게는 존재 자체가 숨겨지므로 404 가 먼저다
            expectError(cloneRoom(cloner, priv), 404, "CHALLENGE_NOT_FOUND");
            expectError(cloneRoom(cloner, solo), 404, "CHALLENGE_NOT_FOUND");
            // 볼 수 있는 사람(방장)에게는 "복제 불가"로 답한다
            expectError(cloneRoom(owner.token(), priv), 403, "NOT_CLONEABLE");
            expectError(cloneRoom(owner.token(), solo), 403, "NOT_CLONEABLE");
        }
    }
}
