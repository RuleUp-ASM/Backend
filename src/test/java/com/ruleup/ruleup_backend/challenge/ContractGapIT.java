package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.moderation.ContentModerationClient;
import com.ruleup.ruleup_backend.moderation.ModerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 안드로이드가 실제로 부딪힌 계약 구멍들.
 *
 * <p>여기 모인 것들의 공통점은 <b>서버가 200 을 주는데 화면이 만들어지지 않는다</b>는 점이다 —
 * 에러가 나지 않으니 서버 테스트는 통과하고, 클라이언트만 막힌다. 그래서 응답 <b>내용</b>을 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContractGapIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ContentModerationClient moderation;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbc; }

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        when(moderation.moderateNickname(anyString())).thenReturn(ModerationResult.APPROVED);
        when(moderation.moderateImage(anyString())).thenReturn(ModerationResult.APPROVED);
    }

    @Nested
    @DisplayName("진행률 목록")
    class Progress {

        @Test
        @DisplayName("끝난 방은 ACTIVE 목록에서 빠지고 ALL 에는 남는다")
        void completedRoomLeavesActiveList() throws Exception {
            Member me = member(uniq("gap-progress"));
            UUID running = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            UUID ended = insertChallenge(me.id(), "EXERCISE", "COMPLETED", "GROUP");
            insertActiveMembership(running, me.id(), "OWNER");
            insertActiveMembership(ended, me.id(), "OWNER");

            // 종료 배치는 방만 마감하고 멤버십은 ACTIVE 로 남긴다 — 그 상태를 그대로 재현한다.
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM challenge_members WHERE challenge_id=?", String.class, bytes(ended)))
                    .as("멤버십은 여전히 ACTIVE 다").isEqualTo("ACTIVE");

            List<?> active = read(getAuth("/api/v1/verifications/progress?status=ACTIVE", me.token()),
                    "$.data.challenges");
            assertThat(active).hasSize(1);
            assertThat((String) read(getAuth("/api/v1/verifications/progress?status=ACTIVE", me.token()),
                    "$.data.challenges[0].challengeId")).isEqualTo(running.toString());

            assertThat((List<?>) read(getAuth("/api/v1/verifications/progress?status=ALL", me.token()),
                    "$.data.challenges")).as("ALL 은 끝난 방도 준다").hasSize(2);
        }
    }

    @Nested
    @DisplayName("신고한 챌린지 가리기")
    class Masking {

        @Test
        @DisplayName("참여 중인 방을 신고하면 상세·방·목록·진행률이 모두 가려진 값으로 내려온다")
        void reportedRoomIsMaskedEverywhere() throws Exception {
            Member me = member(uniq("gap-mask"));
            UUID id = insertChallenge(me.id(), "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(id, me.id(), "OWNER");
            jdbc.update("UPDATE challenges SET title='원래 제목', ai_title='AI 임시 제목', " +
                    "description='원래 설명', image_url='https://cdn.example/a.png' WHERE id=?", bytes(id));

            assertThat((String) read(getAuth("/api/v1/challenges/" + id, me.token()), "$.data.title"))
                    .as("신고 전에는 원문이다").isEqualTo("원래 제목");

            var reported = postJsonAuth("/api/v1/reports", me.token(),
                    Map.of("targetType", "CHALLENGE", "targetChallengeId", id.toString(),
                            "reason", "INAPPROPRIATE", "contextType", "CHALLENGE_DETAIL"));
            assertThat((String) read(reported, "$.data.hiddenEffect"))
                    .as("참여 중이라 숨기지 않고 가린다").isEqualTo("CHALLENGE_MASKED");

            var detail = getAuth("/api/v1/challenges/" + id, me.token());
            assertThat((String) read(detail, "$.data.title")).isEqualTo("AI 임시 제목");
            assertThat((String) read(detail, "$.data.description")).isNull();
            assertThat((String) read(detail, "$.data.imageUrl")).isNull();

            assertThat((String) read(getAuth("/api/v1/challenges/" + id + "/room", me.token()),
                    "$.data.summary.title"))
                    .as("방 안에서도 같은 값이어야 한다").isEqualTo("AI 임시 제목");

            assertThat((String) read(getAuth("/api/v1/challenges", me.token()),
                    "$.data.challenges[0].title"))
                    .as("내 챌린지 목록도 같다").isEqualTo("AI 임시 제목");

            assertThat((String) read(getAuth("/api/v1/verifications/progress", me.token()),
                    "$.data.challenges[0].title"))
                    .as("진행률 목록도 같다").isEqualTo("AI 임시 제목");
        }
    }

    @Nested
    @DisplayName("증빙 사진 업로드")
    class AppealImage {

        /** 1x1 PNG. 저장 전에 매직넘버를 보므로 진짜 헤더여야 한다. */
        private byte[] png() {
            return java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        }

        @Test
        @DisplayName("part 이름은 image 다 — 프로필·챌린지 이미지와 같다")
        void partNameIsImage() throws Exception {
            Member me = member(uniq("gap-img"));

            var res = mvc.perform(multipart("/api/v1/appeals/images")
                            .file(new MockMultipartFile("image", "proof.png", "image/png", png()))
                            .header("Authorization", "Bearer " + me.token()))
                    .andReturn();

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.imageUrl")).isNotBlank();
        }

        @Test
        @DisplayName("part 이름이 틀리면 400 이다 — 500 이면 서버 장애로 읽힌다")
        void wrongPartNameIsClientError() throws Exception {
            Member me = member(uniq("gap-img-bad"));

            var res = mvc.perform(multipart("/api/v1/appeals/images")
                            .file(new MockMultipartFile("file", "proof.png", "image/png", png()))
                            .header("Authorization", "Bearer " + me.token()))
                    .andReturn();

            // 바인딩 단계에서 끝나므로 컨트롤러는 실행되지도 않는다 — 요청이 잘못된 것이지
            // 서버가 고장난 게 아니다. 여기가 500 이던 탓에 이의·문의 첨부 실패의 원인을
            // 이름 불일치가 아니라 저장소 장애에서 찾았다(QA CS-07).
            assertThat(res.getResponse().getStatus())
                    .as("클라이언트 실수를 500 으로 돌려주면 다음에도 같은 곳을 헤맨다")
                    .isEqualTo(400);
        }
    }
}
