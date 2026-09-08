package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 알림 설정 API 계약 — 공통 4절·6절 #3~#5, 공통 #20(2026-09-08 종결).
 *
 * <p>유형별 토글 모델은 폐기됐다. <b>마스터 1개 + 그룹 3종</b>이 확정이고, 챌린지별 음소거는
 * 이 화면에서 바꾸지 않는다 — 개수만 읽기 전용으로 보여주고 켜고 끄기는 각 챌린지 방에서 한다.
 * 그래서 {@code mutedChallengeIds} 는 GET 응답에만 있고 PATCH 는 받지 않는다.
 *
 * <p>세 계층은 <b>가장 제한적인 것이 이긴다</b>. 다만 어느 계층으로 막혀도 알림 센터에는
 * 그대로 쌓인다 — 설정은 푸시에만 적용된다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationTestQueue.class})
class NotificationSettingsApiIT extends ChallengeApiSupport {

    private static final String PATH = "/api/v1/users/me/notification-settings";

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgreementService agreementService;

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
        return jdbc;
    }


    // ===== 헬퍼 =====

    private record Account(String accessToken, UUID userId) {}

    private Account join(String nickname) throws Exception {
        MvcResult res = signup(uniq("st"), nickname + seq());
        return new Account(read(res, "$.data.accessToken"),
                UUID.fromString(read(res, "$.data.user.id")));
    }

    private MvcResult getSettings(String at) throws Exception {
        return mvc.perform(get(PATH).header("Authorization", "Bearer " + at)).andReturn();
    }

    private MvcResult patchSettings(String at, Object body) throws Exception {
        return mvc.perform(patch(PATH).header("Authorization", "Bearer " + at)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 음소거는 참여 중인 방만 등록할 수 있어 실제 챌린지·ACTIVE 멤버십이 필요하다. */
    private UUID joinedChallenge(UUID userId) {
        UUID challengeId = insertChallenge(userId, "HEALTH", "ACTIVE", "GROUP");
        insertActiveMembership(challengeId, userId, "OWNER");
        return challengeId;
    }

    // =====================================================================
    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("설정한 적이 없으면 전부 ON 이다 — 가입 시 백필하지 않는다")
        void defaultsAreAllOn() throws Exception {
            Account a = join("기본값");
            MvcResult res = getSettings(a.accessToken());

            assertThat((Boolean) read(res, "$.data.pushEnabled")).isTrue();
            assertThat((Boolean) read(res, "$.data.groups.account")).isTrue();
            assertThat((Boolean) read(res, "$.data.groups.challenge")).isTrue();
            assertThat((Boolean) read(res, "$.data.groups.marketing")).isTrue();
            assertThat((List<?>) read(res, "$.data.mutedChallengeIds")).isEmpty();
        }

        @Test
        @DisplayName("유형별 토글은 응답에 없다 — 모델이 마스터+그룹으로 교체됐다")
        void noPerTypeToggles() throws Exception {
            Account a = join("유형없음");
            assertThat(getSettings(a.accessToken()).getResponse().getContentAsString())
                    .doesNotContain("\"types\"");
        }

        @Test
        @DisplayName("잠금 계정도 조회할 수 있다")
        void lockedAccountCanRead() throws Exception {
            Account a = join("잠금설정");
            lock(a.userId());
            assertThat(getSettings(a.accessToken()).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("변경")
    class Patch {

        @Test
        @DisplayName("보낸 필드만 바꾼다 — 나머지는 그대로다")
        void partialUpdate() throws Exception {
            Account a = join("부분수정");

            patchSettings(a.accessToken(), Map.of("groups", Map.of("challenge", false)));

            MvcResult res = getSettings(a.accessToken());
            assertThat((Boolean) read(res, "$.data.groups.challenge")).isFalse();
            assertThat((Boolean) read(res, "$.data.groups.account")).isTrue();
            assertThat((Boolean) read(res, "$.data.pushEnabled")).isTrue();
        }

        @Test
        @DisplayName("마스터를 끌 수 있다 — 끌 수 없는 푸시는 없다")
        void masterOff() throws Exception {
            Account a = join("마스터끔");
            patchSettings(a.accessToken(), Map.of("pushEnabled", false));

            assertThat((Boolean) read(getSettings(a.accessToken()), "$.data.pushEnabled")).isFalse();
        }

        @Test
        @DisplayName("계정 그룹도 끌 수 있다 — 제재 고지 푸시가 막혀도 알림 센터 적재로 고지는 성립한다")
        void accountGroupIsTogglable() throws Exception {
            Account a = join("계정끔");
            assertThat(patchSettings(a.accessToken(), Map.of("groups", Map.of("account", false)))
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("응답이 갱신된 설정 전체를 담는다")
        void respondsWithSettings() throws Exception {
            Account a = join("응답");
            MvcResult res = patchSettings(a.accessToken(), Map.of("pushEnabled", false));

            assertThat((Boolean) read(res, "$.data.settings.pushEnabled")).isFalse();
            assertThat((Boolean) read(res, "$.data.settings.groups.account")).isTrue();
        }

        @Test
        @DisplayName("마케팅 그룹 변경은 약관 수신 동의까지 같은 트랜잭션에서 갱신한다")
        void marketingSyncsConsent() throws Exception {
            Account a = join("마케팅");
            MvcResult res = patchSettings(a.accessToken(),
                    Map.of("groups", Map.of("marketing", false)));

            assertThat((String) read(res, "$.data.marketingConsentSyncedAt")).isNotNull();
            assertThat(agreementService.hasIndividualConsent(a.userId(), AgreementType.MARKETING))
                    .as("설정과 동의 이력이 어긋나면 어느 쪽이 진짜인지 알 수 없게 된다").isFalse();
        }

        @Test
        @DisplayName("마케팅을 건드리지 않으면 동기화 시각도 내려가지 않는다")
        void noSyncWhenMarketingUntouched() throws Exception {
            Account a = join("동기화없음");
            MvcResult res = patchSettings(a.accessToken(), Map.of("pushEnabled", false));

            assertThat((String) read(res, "$.data.marketingConsentSyncedAt")).isNull();
        }

        @Test
        @DisplayName("허용되지 않은 키는 400 NOTIFICATION_GROUP_INVALID — 네 키만 받는다")
        void rejectsUnknownKeys() throws Exception {
            Account a = join("잘못된키");

            expectError(patchSettings(a.accessToken(), Map.of("groups", Map.of("watcher", false))),
                    400, "NOTIFICATION_GROUP_INVALID");
            expectError(patchSettings(a.accessToken(),
                            Map.of("mutedChallengeIds", List.of(UUID.randomUUID().toString()))),
                    400, "NOTIFICATION_GROUP_INVALID");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("챌린지별 음소거")
    class Mutes {

        @Test
        @DisplayName("등록하면 204 이고 조회 목록에 들어온다")
        void register() throws Exception {
            Account a = join("음소거등록");
            UUID challengeId = joinedChallenge(a.userId());

            assertThat(mute(a.accessToken(), challengeId).getResponse().getStatus()).isEqualTo(204);
            assertThat((List<String>) read(getSettings(a.accessToken()), "$.data.mutedChallengeIds"))
                    .containsExactly(challengeId.toString());
        }

        @Test
        @DisplayName("멱등이다 — 이미 음소거된 방에 다시 호출해도 204")
        void idempotentRegister() throws Exception {
            Account a = join("음소거멱등");
            UUID challengeId = joinedChallenge(a.userId());

            mute(a.accessToken(), challengeId);
            assertThat(mute(a.accessToken(), challengeId).getResponse().getStatus()).isEqualTo(204);
            assertThat((List<?>) read(getSettings(a.accessToken()), "$.data.mutedChallengeIds"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("해제도 멱등이다 — 음소거한 적 없는 방을 해제해도 204")
        void idempotentRelease() throws Exception {
            Account a = join("음소거해제");
            UUID challengeId = joinedChallenge(a.userId());

            assertThat(unmute(a.accessToken(), challengeId).getResponse().getStatus()).isEqualTo(204);
            mute(a.accessToken(), challengeId);
            assertThat(unmute(a.accessToken(), challengeId).getResponse().getStatus()).isEqualTo(204);
            assertThat((List<?>) read(getSettings(a.accessToken()), "$.data.mutedChallengeIds"))
                    .isEmpty();
        }

        @Test
        @DisplayName("참여하지 않은 챌린지는 400 CHALLENGE_NOT_JOINED")
        void onlyJoinedChallenges() throws Exception {
            Account mine = join("미참여");
            Account other = join("남의방");
            UUID foreign = joinedChallenge(other.userId());

            expectError(mute(mine.accessToken(), foreign), 400, "CHALLENGE_NOT_JOINED");
        }

        @Test
        @DisplayName("해제는 참여 여부를 따지지 않는다 — 탈퇴한 방의 음소거를 못 지우면 남는다")
        void releaseDoesNotRequireMembership() throws Exception {
            Account mine = join("해제자유");
            Account other = join("남의방2");
            UUID foreign = joinedChallenge(other.userId());

            assertThat(unmute(mine.accessToken(), foreign).getResponse().getStatus()).isEqualTo(204);
        }

        private MvcResult mute(String at, UUID challengeId) throws Exception {
            return mvc.perform(put(PATH + "/mutes/" + challengeId)
                    .header("Authorization", "Bearer " + at)).andReturn();
        }

        private MvcResult unmute(String at, UUID challengeId) throws Exception {
            return mvc.perform(delete(PATH + "/mutes/" + challengeId)
                    .header("Authorization", "Bearer " + at)).andReturn();
        }
    }
}
