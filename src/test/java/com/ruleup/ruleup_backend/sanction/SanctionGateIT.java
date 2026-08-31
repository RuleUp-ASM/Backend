package com.ruleup.ruleup_backend.sanction;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.sanction.domain.FeatureCode;
import com.ruleup.ruleup_backend.sanction.domain.Sanction;
import com.ruleup.ruleup_backend.sanction.domain.SanctionReason;
import com.ruleup.ruleup_backend.sanction.domain.SanctionSource;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 계정 상태와 제재 게이트 — 온보딩 테크 스펙 5-6 · 부록 A, 백오피스 공통 5-3.
 *
 * <p>핵심은 소유권 분리다. {@code users.status}는 <b>ACTIVE·SUSPENDED·WITHDRAWN 3종</b>만 들고,
 * <b>정지의 종류와 기간은 {@code sanctions}가 단독으로 소유</b>한다. 게이트는 status 가
 * SUSPENDED 일 때만 그 테이블을 읽으므로, 정상 사용자의 요청 비용은 status 한 번 조회다.
 *
 * <p>이 구조에는 조용히 깨지는 지점이 셋 있고 각각에 테스트를 붙였다.
 * <ol>
 *   <li><b>제재 생성이 두 문장으로 나뉘면 제재가 조용히 풀린다</b> — 게이트는 SUSPENDED 인데 활성
 *       제재가 없으면 스스로 ACTIVE 로 되돌리도록 방어돼 있기 때문이다(부록 A)</li>
 *   <li><b>해제 배치를 {@code ends_at IS NULL OR ...} 로 쓰면 동결분과 영구 정지가 통째로 풀린다</b></li>
 *   <li><b>탈퇴한 채 시간을 흘려보내면 제재가 소진된다</b> — 잔여 기간을 얼려 막는다</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SanctionGateIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired SanctionRepository sanctionRepository;
    @Autowired BanEntryRepository banEntryRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() {
        return mvc;
    }

    // ===== 헬퍼 =====

    private record Account(String accessToken, UUID userId, String tag) {}

    private Account join(String nickname) throws Exception {
        String tag = uniq("sc");
        String token = issueSignupToken(tag, "inst-" + tag, "dev-" + tag);
        MvcResult res = postJson("/api/v1/auth/signup",
                signupBody(token, nickname + seq(), "inst-" + tag, "dev-" + tag));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return new Account(read(res, "$.data.accessToken"),
                UUID.fromString(read(res, "$.data.user.id")), tag);
    }

    private MvcResult getAuth(String url, String at) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + at)).andReturn();
    }

    private MvcResult postAuth(String url, String at, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + at)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    /** 직권 제재 집행 — 백오피스가 쓸 경로와 같은 것을 테스트가 직접 호출한다. */
    private Sanction impose(UUID userId, SanctionType type, FeatureCode feature, Instant endsAt) {
        return sanctionService.impose(userId, SanctionTrack.DISCRETIONARY, type, feature,
                SanctionReason.REPORT_CONFIRMED, "테스트 제재 사유", SanctionSource.DIRECT, null,
                null, endsAt);
    }

    /** 기간이 지난 제재를 만든다. 준영속 엔티티라 save 로 UPDATE 를 태워야 반영된다. */
    private void expire(UUID sanctionId) {
        Sanction s = sanctionRepository.findById(sanctionId).orElseThrow();
        s.forceEndsAtForTest(Instant.now().minusSeconds(60));
        sanctionRepository.save(s);
    }

    private UserStatus statusOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getStatus();
    }

    // =====================================================================
    @Nested
    @DisplayName("users.status 3종 — 정지의 종류는 sanctions 가 소유한다")
    class StatusModel {

        @Test
        @DisplayName("가입 직후는 ACTIVE 이고 활성 제재가 없다")
        void fresh_account_is_active() throws Exception {
            Account a = join("정상계정");
            assertThat(statusOf(a.userId())).isEqualTo(UserStatus.ACTIVE);
            assertThat(sanctionService.activeSanction(a.userId())).isEmpty();
        }

        @Test
        @DisplayName("제재를 걸면 users.status 가 같은 트랜잭션에서 SUSPENDED 로 함께 전이한다")
        void impose_transitions_status_in_one_transaction() throws Exception {
            Account a = join("제재전이");
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            assertThat(statusOf(a.userId()))
                    .as("sanctions 만 만들면 게이트가 아예 조회하지 않아 제재가 걸리지 않는다")
                    .isEqualTo(UserStatus.SUSPENDED);
        }

        @Test
        @DisplayName("SUSPENDED 인데 활성 제재가 없으면 게이트가 스스로 ACTIVE 로 되돌린다")
        void gate_self_heals_when_no_active_sanction() throws Exception {
            Account a = join("자가복구");
            Sanction s = impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            // 해제 배치가 밀린 상황을 만든다 — 제재는 끝났는데 status 는 아직 SUSPENDED.
            expire(s.getId());

            // 다음 요청에서 게이트가 되돌린다. 사용자가 해제일 이후까지 묶이지 않게 하는 방어다.
            assertThat(getAuth("/api/v1/users/me", a.accessToken()).getResponse().getStatus()).isEqualTo(200);
            assertThat(statusOf(a.userId())).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("활성 제재 판단은 세 경우를 모두 담는다 — 진행 중 · 동결 · 영구 정지")
        void active_sanction_covers_three_cases() throws Exception {
            Account running = join("진행중");
            impose(running.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));
            assertThat(sanctionService.activeSanction(running.userId())).isPresent();

            Account permanent = join("영구정지");
            impose(permanent.userId(), SanctionType.BAN, null, null);   // ends_at 이 null 인 것이 BAN
            assertThat(sanctionService.activeSanction(permanent.userId())).isPresent();

            Account frozen = join("동결");
            Sanction s = impose(frozen.userId(), SanctionType.LOCK, null,
                    Instant.now().plus(Duration.ofDays(30)));
            sanctionService.freezeAll(frozen.userId(), Instant.now());
            assertThat(sanctionRepository.findById(s.getId()).orElseThrow().getFrozenRemainingSec())
                    .isNotNull();
            assertThat(sanctionService.activeSanction(frozen.userId()))
                    .as("동결된 계정이 '제재 없음'으로 보이면 안 된다").isPresent();
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("게이트 — 제재 종류별 차단 범위")
    class Gate {

        @Test
        @DisplayName("LOCK 은 열람 전용 — 조회는 통과하고 상태 변경만 403 ACCOUNT_LOCKED")
        void lock_is_read_only() throws Exception {
            Account a = join("잠금계정");
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            assertThat(getAuth("/api/v1/users/me", a.accessToken()).getResponse().getStatus()).isEqualTo(200);
            expectError(postAuth("/api/v1/challenges", a.accessToken(), Map.of()), 403, "ACCOUNT_LOCKED");
        }

        @Test
        @DisplayName("잠금 상태에서도 제재 이력·알림함·동의 상태는 열린다 — 화이트리스트")
        void lock_whitelist_stays_open() throws Exception {
            Account a = join("화이트리스트");
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            // 잠금 사유와 해제일을 볼 수 없으면 사용자가 상황을 알 방법이 없다.
            assertThat(getAuth("/api/v1/users/me/sanctions", a.accessToken()).getResponse().getStatus())
                    .isEqualTo(200);
            // 제재 고지가 알림함에 쌓이므로 잠금 계정도 열람할 수 있어야 한다.
            assertThat(getAuth("/api/v1/notifications", a.accessToken()).getResponse().getStatus())
                    .isEqualTo(200);
            // 동의 상태는 잠금 여부와 관계없이 유지된다.
            assertThat(getAuth("/api/v1/users/me/agreements", a.accessToken()).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("FEATURE_SUSPENSION 은 지정한 기능만 막고, 그 기능의 고유 코드를 내린다")
        void feature_suspension_blocks_only_that_feature() throws Exception {
            Account a = join("기능정지");
            impose(a.userId(), SanctionType.FEATURE_SUSPENSION, FeatureCode.REPORT,
                    Instant.now().plus(Duration.ofDays(7)));

            // 게이트 자체는 일반적이지만 클라는 화면별로 분기한다. 신고 API 명세가 고유 코드를
            // 정해 뒀으므로 ACCOUNT_SUSPENDED 가 아니라 REPORT_SUSPENDED 를 내린다.
            expectError(postAuth("/api/v1/reports", a.accessToken(),
                    Map.of("targetType", "USER", "targetUserId", UUID.randomUUID().toString(),
                            "contextType", "PROFILE", "reason", "INAPPROPRIATE")),
                    403, "REPORT_SUSPENDED");

            // 신고만 막힌 것이지 잠금이 아니다 — 나머지 쓰기는 그대로 통과해야 한다.
            MvcResult other = postAuth("/api/v1/nicknames/check", a.accessToken(),
                    Map.of("nickname", "기능정지확인"));
            assertThat(other.getResponse().getStatus())
                    .as("기능 정지는 해당 API 만 막는다").isNotEqualTo(403);
        }

        @Test
        @DisplayName("BAN 은 조회까지 전면 403 ACCOUNT_BANNED 이고 로그인 자체가 막힌다")
        void ban_blocks_everything() throws Exception {
            Account a = join("영구정지계정");
            impose(a.userId(), SanctionType.BAN, null, null);

            expectError(getAuth("/api/v1/users/me", a.accessToken()), 403, "ACCOUNT_BANNED");
            expectError(postJson("/api/v1/auth/oauth/kakao",
                    loginBody(a.tag(), "inst-" + a.tag(), "dev-" + a.tag())), 403, "ACCOUNT_BANNED");
        }

        @Test
        @DisplayName("제재 중에도 로그아웃과 탈퇴는 허용된다 — 막으면 계정에 갇힌다")
        void logout_and_withdraw_always_allowed() throws Exception {
            Account a = join("탈출경로");
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/users/me")
                    .header("Authorization", "Bearer " + a.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(Map.of("confirmPhrase", "탈퇴할게요")))).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("제재 잔여 기간 동결 — 탈퇴로 시간을 흘려보내는 경로를 막는다")
    class Freeze {

        @Test
        @DisplayName("탈퇴 시 잔여 초를 얼리고 ends_at 을 비운다")
        void withdraw_freezes_remaining() throws Exception {
            Account a = join("동결탈퇴");
            Sanction s = impose(a.userId(), SanctionType.LOCK, null,
                    Instant.now().plus(Duration.ofDays(30)));

            withdraw(a);

            Sanction after = sanctionRepository.findById(s.getId()).orElseThrow();
            assertThat(after.getEndsAt()).isNull();
            assertThat(after.getFrozenRemainingSec())
                    .as("종료 시각이 아니라 기간으로 저장해야 시간이 흘러도 줄지 않는다")
                    .isNotNull().isGreaterThan(0);
        }

        @Test
        @DisplayName("복원하면 잔여 기간만큼 다시 카운트다운이 시작된다")
        void restore_thaws_remaining() throws Exception {
            Account a = join("동결복원");
            Sanction s = impose(a.userId(), SanctionType.LOCK, null,
                    Instant.now().plus(Duration.ofDays(30)));
            withdraw(a);
            int frozen = sanctionRepository.findById(s.getId()).orElseThrow().getFrozenRemainingSec();

            restoreByLogin(a);

            Sanction after = sanctionRepository.findById(s.getId()).orElseThrow();
            assertThat(after.getFrozenRemainingSec()).isNull();
            assertThat(after.getEndsAt()).isNotNull();
            assertThat(Duration.between(Instant.now(), after.getEndsAt()).toSeconds())
                    .isCloseTo(frozen, org.assertj.core.data.Offset.offset(120L));
            assertThat(statusOf(a.userId()))
                    .as("제재가 남아 있으면 SUSPENDED 로 복귀한다").isEqualTo(UserStatus.SUSPENDED);
        }

        @Test
        @DisplayName("영구 정지는 동결 대상이 아니다 — ends_at 과 frozen 이 둘 다 null 인 것이 BAN 이다")
        void ban_is_not_frozen() throws Exception {
            Account a = join("영구동결제외");
            Sanction s = impose(a.userId(), SanctionType.BAN, null, null);

            withdraw(a);

            Sanction after = sanctionRepository.findById(s.getId()).orElseThrow();
            assertThat(after.getEndsAt()).isNull();
            assertThat(after.getFrozenRemainingSec()).isNull();
        }

        @Test
        @DisplayName("복원하면 점수와 티어도 그대로 살아난다")
        void restore_keeps_score_and_tier() throws Exception {
            Account a = join("점수복원");
            MvcResult before = getAuth("/api/v1/users/me", a.accessToken());
            int score = read(before, "$.data.user.score");
            String tier = read(before, "$.data.user.tier");

            withdraw(a);
            MvcResult restored = restoreByLogin(a);

            assertThat((Integer) read(restored, "$.data.user.score")).isEqualTo(score);
            assertThat((String) read(restored, "$.data.user.tier")).isEqualTo(tier);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("해제 배치 — 조건문을 넓게 쓰면 제재가 통째로 풀린다")
    class Release {

        @Test
        @DisplayName("기간이 지난 제재만 해제하고 계정을 ACTIVE 로 되돌린다")
        void releases_expired_only() throws Exception {
            Account a = join("해제대상");
            Sanction s = impose(a.userId(), SanctionType.LOCK, null,
                    Instant.now().plus(Duration.ofDays(30)));
            expire(s.getId());

            sanctionService.releaseExpired(Instant.now());

            assertThat(statusOf(a.userId())).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("동결된 제재와 영구 정지는 해제 배치가 건드리지 않는다")
        void never_releases_frozen_or_ban() throws Exception {
            Account banned = join("배치영구");
            impose(banned.userId(), SanctionType.BAN, null, null);

            Account frozen = join("배치동결");
            impose(frozen.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));
            sanctionService.freezeAll(frozen.userId(), Instant.now());

            sanctionService.releaseExpired(Instant.now());

            assertThat(sanctionService.activeSanction(banned.userId()))
                    .as("ends_at IS NULL 을 만료로 보면 영구 정지가 풀린다").isPresent();
            assertThat(sanctionService.activeSanction(frozen.userId()))
                    .as("동결분도 ends_at 이 null 이라 같은 조건문에 휩쓸린다").isPresent();
        }

        @Test
        @DisplayName("다른 활성 제재가 남아 있으면 SUSPENDED 를 유지한다")
        void keeps_suspended_when_another_sanction_lives() throws Exception {
            Account a = join("복수제재");
            Sanction expiring = impose(a.userId(), SanctionType.FEATURE_SUSPENSION, FeatureCode.REPORT,
                    Instant.now().plus(Duration.ofDays(7)));
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            expire(expiring.getId());
            sanctionService.releaseExpired(Instant.now());

            assertThat(statusOf(a.userId())).isEqualTo(UserStatus.SUSPENDED);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("영구 정지 재가입 차단 — 계정과 생명주기가 분리된 해시")
    class BanList {

        @Test
        @DisplayName("BAN 전이 시 솔트 해시를 밴리스트에 남긴다 — 원본 식별자는 보관하지 않는다")
        void ban_records_salted_hash() throws Exception {
            Account a = join("밴리스트");
            long before = banEntryRepository.count();

            impose(a.userId(), SanctionType.BAN, null, null);

            assertThat(banEntryRepository.count()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("밴리스트에 걸린 소셜 계정은 로그인 시점에 403 ACCOUNT_BANNED")
        void banned_social_account_cannot_return() throws Exception {
            Account a = join("재가입차단");
            impose(a.userId(), SanctionType.BAN, null, null);

            expectError(postJson("/api/v1/auth/oauth/kakao",
                    loginBody(a.tag(), "inst-" + a.tag(), "dev-" + a.tag())), 403, "ACCOUNT_BANNED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("GET /users/me/sanctions — 제재 통지·이력 열람")
    class MySanctions {

        @Test
        @DisplayName("제재가 없으면 activeSanction 이 null 이고 accountStatus 는 ACTIVE")
        void empty_when_clean() throws Exception {
            Account a = join("이력없음");
            MvcResult res = getAuth("/api/v1/users/me/sanctions", a.accessToken());

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) read(res, "$.data.accountStatus")).isEqualTo("ACTIVE");
            assertThat((Object) read(res, "$.data.activeSanction")).isNull();
            assertThat((List<?>) read(res, "$.data.admin")).isEmpty();
            assertThat((List<?>) read(res, "$.data.auto")).isEmpty();
        }

        @Test
        @DisplayName("자동 제재와 직권 제재를 별개 배열로 내리며 합산하지 않는다")
        void auto_and_admin_are_separate_tracks() throws Exception {
            Account a = join("트랙분리");
            impose(a.userId(), SanctionType.LOCK, null, Instant.now().plus(Duration.ofDays(30)));

            MvcResult res = getAuth("/api/v1/users/me/sanctions", a.accessToken());
            assertThat((List<?>) read(res, "$.data.admin")).hasSize(1);
            assertThat((String) read(res, "$.data.activeSanction.track")).isEqualTo("ADMIN");
            assertThat((String) read(res, "$.data.activeSanction.type")).isEqualTo("LOCK");
            assertThat((String) read(res, "$.data.activeSanction.reasonText")).isNotBlank();
        }

        @Test
        @DisplayName("영구 정지의 endsAt 은 null 이다 — 해제일이 없다")
        void ban_has_null_ends_at() throws Exception {
            Account a = join("영구조회");
            impose(a.userId(), SanctionType.BAN, null, null);

            // BAN 은 전면 차단이라 본인도 이 API 에 도달하지 못한다 — 서비스 계층으로 확인한다.
            var active = sanctionService.activeSanction(a.userId()).orElseThrow();
            assertThat(active.getType()).isEqualTo(SanctionType.BAN);
            assertThat(active.getEndsAt()).isNull();
            assertThat(active.getFrozenRemainingSec()).isNull();
        }

        @Test
        @DisplayName("타인의 제재 이력을 조회할 경로 자체를 두지 않는다 — userId 를 받지 않는다")
        void no_path_for_other_users() throws Exception {
            Account a = join("타인차단");
            MvcResult res = getAuth("/api/v1/users/" + UUID.randomUUID() + "/sanctions", a.accessToken());
            assertThat(res.getResponse().getStatus())
                    .as("경로를 만들지 않는 것이 권한 검사보다 확실한 방어다").isEqualTo(404);
        }

        @Test
        @DisplayName("미인증이면 401 LOGIN_REQUIRED")
        void unauthenticated_401() throws Exception {
            expectError(mvc.perform(get("/api/v1/users/me/sanctions")).andReturn(), 401, "LOGIN_REQUIRED");
        }
    }

    // ===== 공통 =====

    private void withdraw(Account a) throws Exception {
        MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + a.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(Map.of("confirmPhrase", "탈퇴할게요")))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    /** 같은 소셜 계정으로 다시 로그인 → 계정 복원. */
    private MvcResult restoreByLogin(Account a) throws Exception {
        MvcResult login = postJson("/api/v1/auth/oauth/kakao",
                loginBody(a.tag(), "inst-" + a.tag(), "dev-" + a.tag()));
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        if (Boolean.TRUE.equals(read(login, "$.data.isNewUser"))) {
            String token = read(login, "$.data.signupToken");
            return postJson("/api/v1/auth/signup",
                    signupBody(token, "복원" + seq(), "inst-" + a.tag(), "dev-" + a.tag()));
        }
        return login;
    }
}
