package com.ruleup.ruleup_backend.auth.review.seed;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.TestcontainersConfiguration.MutableClock;
import com.ruleup.ruleup_backend.agreement.AgreementService;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.auth.review.ReviewAccountPolicy;
import com.ruleup.ruleup_backend.auth.review.ReviewCodes;
import com.ruleup.ruleup_backend.auth.review.ReviewLoginProperties;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedException.Reason;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedService.CreatePlan;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedService.CreateResult;
import com.ruleup.ruleup_backend.auth.review.seed.ReviewSeedService.ResetResult;
import com.ruleup.ruleup_backend.auth.service.TokenService;
import com.ruleup.ruleup_backend.challenge.ChallengeApiSupport;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.notification.domain.NotificationType;
import com.ruleup.ruleup_backend.sanction.ReviewAccounts;
import com.ruleup.ruleup_backend.score.service.ScoreProcessor;
import com.ruleup.ruleup_backend.user.UserAccountService;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.ByteBuffer;
import java.sql.ResultSetMetaData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ruleup.ruleup_backend.user.UserAccountService.CONFIRM_PHRASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 심사 계정 시드 — {@code create}(새 회차) · {@code reset}(같은 회차 원상 복구) (심사 계정 테크 스펙 5-3 · 5-7 · 5-8).
 *
 * <p>모드별 계약 — 어느 모드에서 기존 행을 허용하는가 — 이 곧 「지난 회차 계정이 다음 심사위원에게 넘어가지 않는다」는
 * 보장의 절반이다(나머지 절반은 코드 로그인의 subject 회차 대조 — ReviewNextRoundIT).
 * <ul>
 *   <li><b>create</b>: round 를 실행일(KST) + 순번으로 <b>발급</b>하고 새 UUID 계정과 데모 데이터를 만든다. 같은 ID 나 그 round 의
 *       subject 가 하나라도 있으면 아무 행도 남기지 않고 실패한다. 출력은 round 와 UUID 뿐 — 코드는 만들지도 받지도 않는다</li>
 *   <li><b>reset</b>: round 를 인자로 받지 않고 서버와 같은 설정({@link ReviewAccountPolicy})에서 읽는다. 대상은 그 설정 codes 의
 *       userId 이면서 subject 회차가 그 round 인 계정. 하나라도 어긋나면 아무것도 바꾸지 않고 실패한다</li>
 *   <li>공통: 기존 행의 {@code oauth_subject} 를 고치지 않는다</li>
 * </ul>
 *
 * <h4>테스트가 정하는 구현 결정</h4>
 * <ul>
 *   <li>계정 수는 인자(오픈 이슈 #2 — 심사위원 5 + 예비). 예비 계정도 같은 구성이다</li>
 *   <li>데모 그룹방의 방장은 <b>더미 멤버</b> — 심사 계정이 방장이면 한 심사위원의 나가기·탈퇴가 다른 심사위원의 방을 바꾼다</li>
 *   <li>더미 멤버({@code ruleup-demo-NN})는 회차와 무관하게 유지 — 있으면 다시 쓰고 모자라면 더 만든다</li>
 *   <li>공지·댓글은 심지 않는다 — 방 공지·댓글 테이블은 Phase 2 에서 내려갔다</li>
 *   <li>같은 날 같은 순번 계정이면 이력이 같다 — 리셋하면 생성 직후와 점수·티어가 같다(날짜는 실행일 기준 상대값)</li>
 * </ul>
 *
 * <p>리셋의 설정은 테스트가 직접 만든 {@link ReviewAccountPolicy} 로 넘긴다 — 운영 태스크는 서버와 같은 빈을 넘긴다.
 * 삭제 범위 검증의 기준({@code REVIEW_ACCOUNT_IDS})도 같은 방식으로 {@link ReviewAccounts} 를 넘긴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewSeedServiceIT extends ChallengeApiSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 코드 해시 모양 — 시드 출력에 있으면 안 된다. */
    private static final Pattern HEX64 = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])");
    /** 코드 원문 모양(XXXX-XXXX-XXXX) — 시드 출력에 있으면 안 된다. */
    private static final Pattern CODE_LIKE = Pattern.compile("(?<![A-Z0-9])[A-Z0-9]{4}-?[A-Z0-9]{4}-?[A-Z0-9]{4}(?![A-Z0-9])");
    private static final List<AgreementType> TERMS = List.of(AgreementType.TOS, AgreementType.PRIVACY,
            AgreementType.LOCATION, AgreementType.MARKETING, AgreementType.EVENT);

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReviewSeedService seed;
    @Autowired ScoreProcessor scoreProcessor;
    @Autowired AgreementService agreementService;
    @Autowired AppProperties props;
    @Autowired UserRepository userRepository;
    @Autowired UserAccountService userAccountService;
    @Autowired TokenService tokenService;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        clock.reset();
    }

    /** 생성 결과를 여러 테스트가 읽기만 한다 — 데모 데이터 생성이 무거워 한 번만 만든다. 리셋 테스트는 자기 회차를 따로 만든다. */
    private static CreateResult shared;

    private CreateResult created() {
        if (shared == null) shared = seed.create(3, evaluationEnd());
        return shared;
    }

    // =====================================================================
    // create
    // =====================================================================

    @Nested
    @DisplayName("create — round 발급")
    class RoundIssue {

        @Test
        @DisplayName("round 는 실행일(KST) yyyyMMdd + 순번 2자리이고, 서버 설정 검사를 그대로 통과한다")
        void issuesRoundFromRunDate() {
            CreateResult res = created();

            assertThat(res.round()).matches(today().format(YMD) + "\\d{2}");
            // 출력된 round 와 UUID 로 Secrets Manager 설정을 만들면 기동 검사(형식·날짜·60일 창)를 통과하고
            // 만든 계정이 모두 「이번 회차 계정」으로 보인다.
            ReviewAccountPolicy policy = config(res.round(), res.accountIds(), scopeOf(res), true, expiry(), clock);
            for (UUID id : res.accountIds()) {
                assertThat(policy.isCurrentRoundAccount(subjectOf(id))).as("이번 회차 계정 %s", id).isTrue();
            }
        }

        @Test
        @DisplayName("같은 날 두 번째 실행은 순번이 오른다 — 같은 round 로 계정을 두 벌 만들 수 없다")
        void secondRunSameDayIncrementsSequence() {
            CreateResult first = seed.create(1, evaluationEnd());
            CreateResult second = seed.create(1, evaluationEnd());

            assertThat(second.round().substring(0, 8)).isEqualTo(first.round().substring(0, 8));
            assertThat(Integer.parseInt(second.round().substring(8)))
                    .isEqualTo(Integer.parseInt(first.round().substring(8)) + 1);
            assertThat(second.accountIds()).doesNotContainAnyElementsOf(first.accountIds());
        }

        @Test
        @DisplayName("그날 순번 99 를 다 쓰면 실패한다 — 다른 날짜로 넘기거나 순번을 되돌려 쓰지 않는다")
        void failsWhenSequenceExhausted() {
            clock.advance(Duration.ofDays(400));   // 다른 테스트의 회차와 겹치지 않는 날
            try {
                String date = today().format(YMD);
                insertBareUser(UUID.randomUUID(), "ruleup-review-" + date + "99-01");
                long users = count("SELECT COUNT(*) FROM users");

                assertThatThrownBy(() -> seed.create(1, today().plusDays(14)))
                        .isInstanceOfSatisfying(ReviewSeedException.class,
                                e -> assertThat(e.reason()).isEqualTo(Reason.ROUND_EXHAUSTED));
                assertThat(count("SELECT COUNT(*) FROM users")).isEqualTo(users);
            } finally {
                clock.reset();
            }
        }

        @Test
        @DisplayName("평가 종료일이 오늘 이전이면 거절한다 — 진행 중 방의 종료일을 평가 종료일 뒤로 잡을 수 없다")
        void rejectsPastEvaluationEnd() {
            assertThatThrownBy(() -> seed.create(1, today()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("create — 기존 행을 허용하지 않는다")
    class CreateRefusesExistingRows {

        @Test
        @DisplayName("계획 단계는 아무것도 쓰지 않는다")
        void planWritesNothing() {
            Map<String, Long> before = tableCounts();

            CreatePlan plan = seed.plan(2, evaluationEnd());

            assertThat(plan.round()).matches(today().format(YMD) + "\\d{2}");
            assertThat(plan.accountIds()).hasSize(2).doesNotHaveDuplicates();
            assertThat(tableCounts()).isEqualTo(before);
        }

        @Test
        @DisplayName("같은 ID 의 행이 있으면 전체 롤백하고 실패한다 — 계정·더미·방·이력 어느 것도 남지 않는다")
        void failsOnSameId() {
            CreatePlan plan = seed.plan(3, evaluationEnd());
            insertBareUser(plan.accountIds().get(1), "ruleup-collide-" + UUID.randomUUID());
            Map<String, Long> before = tableCounts();

            assertThatThrownBy(() -> seed.apply(plan))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.ACCOUNT_EXISTS));
            assertThat(tableCounts()).isEqualTo(before);
            assertThat(count("SELECT COUNT(*) FROM users WHERE oauth_subject LIKE ?", "ruleup-review-" + plan.round() + "-%"))
                    .isZero();
        }

        @Test
        @DisplayName("그 round 의 subject 가 하나라도 있으면 전체 롤백하고 실패한다 — 동시에 돈 두 번째 실행을 막는다")
        void failsOnSameRoundSubject() {
            CreatePlan plan = seed.plan(2, evaluationEnd());
            // 계획과 적용 사이에 다른 실행이 같은 round 로 계정을 만든 상황 — 순번이 겹치지 않아도 막는다
            UUID other = UUID.randomUUID();
            insertBareUser(other, "ruleup-review-" + plan.round() + "-07");
            String subjectBefore = subjectOf(other);
            Map<String, Long> before = tableCounts();

            assertThatThrownBy(() -> seed.apply(plan))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.ROUND_TAKEN));
            assertThat(tableCounts()).isEqualTo(before);
            assertThat(subjectOf(other)).as("기존 행의 subject 를 고치지 않는다").isEqualTo(subjectBefore);
        }
    }

    @Nested
    @DisplayName("create — 출력")
    @ExtendWith(OutputCaptureExtension.class)
    class CreateOutput {

        @Test
        @DisplayName("결과·로그에는 round 와 UUID 만 — 코드·해시가 없다")
        void outputsRoundAndIdsOnly(CapturedOutput output) {
            CreateResult res = seed.create(2, evaluationEnd());

            List<String> seedLines = output.getAll().lines().filter(l -> l.contains("review_seed")).toList();
            assertThat(seedLines).as("시드 로그").isNotEmpty();
            String joined = String.join("\n", seedLines);
            assertThat(joined).contains(res.round());
            for (UUID id : res.accountIds()) assertThat(joined).contains(id.toString());
            for (UUID id : res.dummyIds()) assertThat(joined).contains(id.toString());
            assertThat(HEX64.matcher(joined).find()).as("해시 모양 없음").isFalse();
            assertThat(CODE_LIKE.matcher(joined.toUpperCase()).find()).as("코드 모양 없음").isFalse();

            // 결과 타입에도 코드·해시 자리가 없다 — 시드는 코드를 만들지도 받지도 않는다(5-7)
            for (Class<?> type : List.of(CreateResult.class, CreatePlan.class)) {
                assertThat(Arrays.stream(type.getRecordComponents()).map(c -> c.getName().toLowerCase()))
                        .noneMatch(n -> n.contains("code") || n.contains("hash"));
            }
        }
    }

    @Nested
    @DisplayName("create — 심사 계정 행 구성(5-3)")
    class AccountRows {

        @Test
        @DisplayName("새 UUIDv7 · GOOGLE · ruleup-review-{round}-NN · review-inst-{round}-NN · device_id NULL · 기기 정보 없음 · ACTIVE")
        void identityRows() {
            CreateResult res = created();
            assertThat(res.accountIds()).hasSize(3).doesNotHaveDuplicates();

            for (int i = 0; i < res.accountIds().size(); i++) {
                UUID id = res.accountIds().get(i);
                String seq = String.format("%02d", i + 1);
                Map<String, Object> u = row("SELECT oauth_provider, oauth_subject, status, installation_id, device_id, "
                        + "platform, device_model, device_info_updated_at, profile_changed_at, nickname_changed_at "
                        + "FROM users WHERE id = ?", id);

                assertThat(id.version()).as("UUIDv7").isEqualTo(7);
                assertThat(u.get("oauth_provider")).isEqualTo(OAuthProvider.GOOGLE.name());
                assertThat(u.get("oauth_subject")).isEqualTo("ruleup-review-" + res.round() + "-" + seq);
                assertThat(u.get("status")).isEqualTo("ACTIVE");
                assertThat(u.get("installation_id")).isEqualTo("review-inst-" + res.round() + "-" + seq);
                assertThat(u.get("device_id")).as("sync 활성 기기 대조를 건너뛴다").isNull();
                assertThat(u.get("platform")).isNull();
                assertThat(u.get("device_model")).isNull();
                assertThat(u.get("device_info_updated_at")).isNull();
                assertThat(u.get("profile_changed_at")).as("변경 잠금 없음").isNull();
                assertThat(u.get("nickname_changed_at")).isNull();
            }
        }

        @Test
        @DisplayName("닉네임 APPROVED(심사 중으로 시작하지 않음) · 서로 겹치지 않음 · 생일(만 14세 이상)·성별")
        void profileRows() {
            CreateResult res = created();
            Set<String> nicknames = new HashSet<>();

            for (UUID id : res.accountIds()) {
                Map<String, Object> u = row("SELECT nickname, approved_nickname, nickname_status FROM users WHERE id = ?", id);
                assertThat(u.get("nickname_status")).isEqualTo("APPROVED");
                assertThat(u.get("nickname")).isEqualTo(u.get("approved_nickname"));
                assertThat(nicknames.add((String) u.get("nickname"))).isTrue();
                assertThat(count("SELECT COUNT(*) FROM users WHERE approved_nickname = ? AND id <> ? AND status <> 'WITHDRAWN'",
                        u.get("approved_nickname"), bytes(id))).as("다른 계정과 겹치지 않음").isZero();

                Map<String, Object> info = row("SELECT birth_date, gender FROM user_information WHERE user_id = ?", id);
                LocalDate birth = localDate(info.get("birth_date"));
                assertThat(Period.between(birth, today()).getYears()).isGreaterThanOrEqualTo(14);
                assertThat(info.get("gender")).isNotNull();
            }
        }

        @Test
        @DisplayName("약관 5종 현행 버전 동의(상태·이력 둘 다) · 개별 동의 2종 행 없음 · 소셜 토큰·푸시 토큰·RT 없음")
        void agreementsAndTokens() {
            CreateResult res = created();

            for (UUID id : res.accountIds()) {
                Map<String, Object> states = new LinkedHashMap<>();
                jdbcTemplate.query("SELECT agreement_type, agreed, version FROM user_agreement_states WHERE user_id = ?",
                        rs -> { states.put(rs.getString(1), rs.getBoolean(2) + "@" + rs.getString(3)); }, (Object) bytes(id));
                Map<String, Object> expected = new LinkedHashMap<>();
                for (AgreementType t : TERMS) expected.put(t.name(), "true@" + props.client().termsVersions().of(t));
                assertThat(states).as("약관 5종만, 개별 동의 2종 없음").isEqualTo(expected);

                for (AgreementType t : TERMS) {
                    assertThat(count("SELECT COUNT(*) FROM user_agreement_events WHERE user_id = ? AND agreement_type = ? AND agreed = 1",
                            bytes(id), t.name())).as("동의 이력 %s", t).isEqualTo(1);
                }
                assertThat(count("SELECT COUNT(*) FROM social_tokens WHERE user_id = ?", bytes(id))).isZero();
                assertThat(count("SELECT COUNT(*) FROM DeviceToken WHERE userId = ?", bytes(id))).isZero();
                assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", bytes(id))).isZero();
            }
        }
    }

    @Nested
    @DisplayName("create — 데모 데이터(5-3 데모 데이터 구성)")
    class DemoData {

        @Test
        @DisplayName("수동 인증 그룹방 1 · 걸음 자동 인증 그룹방 1 — 진행 중 · 공개(탐색에 보임) · 종료일은 평가 종료일 뒤")
        void rooms() {
            CreateResult res = created();
            assertThat(res.manualRoomId()).isNotEqualTo(res.autoRoomId());

            Map<String, Object> manual = room(res.manualRoomId());
            assertThat(manual.get("mode")).isEqualTo("GROUP");
            assertThat(manual.get("status")).isEqualTo("ACTIVE");
            assertThat(manual.get("visibility")).isEqualTo("PUBLIC");
            assertThat(manual.get("deleted_at")).isNull();
            assertThat(manual.get("sel")).isEqualTo("MANUAL");
            assertThat(localDate(manual.get("end_date"))).isAfter(evaluationEnd());

            Map<String, Object> auto = room(res.autoRoomId());
            assertThat(auto.get("mode")).isEqualTo("GROUP");
            assertThat(auto.get("status")).isEqualTo("ACTIVE");
            assertThat(auto.get("visibility")).isEqualTo("PUBLIC");
            assertThat(auto.get("deleted_at")).isNull();
            assertThat(((Number) auto.get("template_id")).longValue()).as("하루 만 보 걷기").isEqualTo(1201L);
            assertThat(auto.get("sel")).isEqualTo("AUTO");
            assertThat(auto.get("vt")).isEqualTo("HEALTH_CONNECT");
            assertThat(((Number) auto.get("steps")).intValue()).isEqualTo(10000);
            assertThat(localDate(auto.get("end_date"))).isAfter(evaluationEnd());

            for (UUID roomId : List.of(res.manualRoomId(), res.autoRoomId())) {
                assertThat(count("SELECT COUNT(*) FROM challenge_stats WHERE challenge_id = ?", bytes(roomId)))
                        .as("탐색 투영 행").isEqualTo(1);
            }
        }

        @Test
        @DisplayName("방마다 심사 계정 전원 + 더미 멤버 4~6명 · 방장은 더미 멤버 · 참여 인원 수가 실제 멤버와 맞다")
        void members() {
            CreateResult res = created();

            for (UUID roomId : List.of(res.manualRoomId(), res.autoRoomId())) {
                Map<UUID, String> active = activeMembers(roomId);
                assertThat(active.keySet()).containsAll(res.accountIds());
                for (UUID id : res.accountIds()) assertThat(active.get(id)).isEqualTo("MEMBER");

                Set<UUID> dummies = new LinkedHashSet<>(active.keySet());
                res.accountIds().forEach(dummies::remove);
                assertThat(dummies).hasSizeBetween(4, 6);
                assertThat(res.dummyIds()).containsAll(dummies);
                for (UUID d : dummies) {
                    assertThat(subjectOf(d)).startsWith("ruleup-demo-");
                    assertThat(statusOf(d)).isEqualTo("ACTIVE");
                }

                Map<String, Object> r = room(roomId);
                assertThat(r.get("owner_type")).isEqualTo("USER");
                UUID owner = uuid(r.get("owner_id"));
                assertThat(dummies).as("방장은 더미 멤버").contains(owner);
                assertThat(active.get(owner)).isEqualTo("OWNER");
                assertThat(((Number) r.get("participant_count")).intValue()).isEqualTo(active.size());
            }
            // 자동 인증 방은 셋업이 끝난 상태 — PENDING_SETUP 이면 결과가 화면에 뜨지 않는다
            assertThat(count("SELECT COUNT(*) FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE' "
                    + "AND setup_status <> 'READY'", bytes(res.autoRoomId()))).isZero();
        }

        @Test
        @DisplayName("자동 인증 방 이력 — 최근 4주 · 성공·실패·이의 인용이 섞여 있다")
        void autoRoomHistory() {
            CreateResult res = created();

            for (UUID id : res.accountIds()) {
                List<Map<String, Object>> rows = finalRows(res.autoRoomId(), id);
                assertWithinLastFourWeeks(rows);
                assertThat(statuses(rows, "SUCCESS", "AUTO")).as("자동 성공").isPositive();
                assertThat(statuses(rows, "FAILED", null)).as("실패").isPositive();
                assertThat(statuses(rows, "SUCCESS", "APPEAL")).as("이의 인용").isPositive();

                // 이의 인용 행마다 인용된 이의 행이 있다
                for (Map<String, Object> r : rows) {
                    if (!"APPEAL".equals(r.get("verifiedVia"))) continue;
                    assertThat(count("SELECT COUNT(*) FROM verification_appeals WHERE verificationDailyId = ? "
                            + "AND acceptedAt IS NOT NULL", r.get("idBytes"))).isEqualTo(1);
                }
            }
        }

        @Test
        @DisplayName("수동 인증 방 이력 — 최근 4주 · 성공 위주(80% 이상) · 마지막 확정일까지 연속 성공 7일 이상")
        void manualRoomHistory() {
            CreateResult res = created();

            for (UUID id : res.accountIds()) {
                List<Map<String, Object>> rows = finalRows(res.manualRoomId(), id);
                assertWithinLastFourWeeks(rows);
                long success = rows.stream().filter(r -> "SUCCESS".equals(r.get("status"))).count();
                assertThat((double) success / rows.size()).isGreaterThanOrEqualTo(0.8);
                assertThat(rows.stream().filter(r -> "SUCCESS".equals(r.get("status"))).map(r -> r.get("verifiedVia")))
                        .allMatch("MANUAL"::equals);

                int streak = 0;
                for (int i = rows.size() - 1; i >= 0 && "SUCCESS".equals(rows.get(i).get("status")); i--) streak++;
                assertThat(streak).as("스트릭").isGreaterThanOrEqualTo(7);
            }
        }

        @Test
        @DisplayName("이력은 가입·참여·방 시작보다 뒤 · 행 id 가 날짜 순 — 점수 동기화가 id 순으로 읽는다")
        void historyOrdering() {
            CreateResult res = created();

            for (UUID roomId : List.of(res.manualRoomId(), res.autoRoomId())) {
                LocalDate start = localDate(room(roomId).get("start_date"));
                for (UUID id : Stream.concat(res.accountIds().stream(), activeMembers(roomId).keySet().stream()).distinct().toList()) {
                    List<LocalDate> byId = jdbcTemplate.query("SELECT targetDate FROM VerificationDaily "
                                    + "WHERE challengeId = ? AND userId = ? ORDER BY id",
                            (rs, n) -> rs.getDate(1).toLocalDate(), bytes(roomId), bytes(id));
                    assertThat(byId).as("이력 %s", id).isNotEmpty().isSorted();
                    LocalDate first = byId.get(0);
                    Instant firstStart = first.atStartOfDay(KST).toInstant();

                    assertThat(start).isBeforeOrEqualTo(first);
                    assertThat(instant("SELECT created_at FROM users WHERE id = ?", id)).isBefore(firstStart);
                    assertThat(instant("SELECT COALESCE(participation_started_at, joined_at) FROM challenge_members "
                            + "WHERE challenge_id = ? AND user_id = ? AND status = 'ACTIVE'", roomId, id))
                            .isBeforeOrEqualTo(firstStart);
                }
            }
        }

        @Test
        @DisplayName("더미 멤버도 이력이 있다 — 방 안 랭킹")
        void dummiesHaveHistory() {
            CreateResult res = created();

            for (UUID roomId : List.of(res.manualRoomId(), res.autoRoomId())) {
                for (UUID d : activeMembers(roomId).keySet()) {
                    if (res.accountIds().contains(d)) continue;
                    assertThat(finalRows(roomId, d)).as("더미 %s", d).hasSizeGreaterThanOrEqualTo(14);
                }
            }
        }

        @Test
        @DisplayName("점수·티어는 정산 로직으로 산출 — 저장 값이 원장 재생과 맞고 원장 행이 있다")
        void scoreFromSettlement() {
            CreateResult res = created();

            for (UUID id : Stream.concat(res.accountIds().stream(), res.dummyIds().stream()).toList()) {
                assertThat(scoreProcessor.verifyUser(id)).as("재생 불일치 %s", id).isEmpty();
            }
            for (UUID id : res.accountIds()) {
                assertThat(count("SELECT COUNT(*) FROM score_transactions WHERE user_id = ?", bytes(id)))
                        .as("숫자만 넣지 않았다").isPositive();
            }
        }

        @Test
        @DisplayName("알림함 — 유형별 샘플 3종 이상 · 푸시로 나가지 않았다")
        void sampleNotifications() {
            CreateResult res = created();

            for (UUID id : res.accountIds()) {
                List<String> types = jdbcTemplate.queryForList(
                        "SELECT DISTINCT type FROM notifications WHERE user_id = ?", String.class, bytes(id));
                assertThat(types).hasSizeGreaterThanOrEqualTo(3);
                types.forEach(NotificationType::valueOf);
                assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND pushed_at IS NOT NULL", bytes(id)))
                        .isZero();
            }
        }

        @Test
        @DisplayName("감시자는 지정하지 않는다 — 실제 번호가 들어가면 SMS 가 나간다")
        void noWatchers() {
            CreateResult res = created();

            for (UUID id : Stream.concat(res.accountIds().stream(), res.dummyIds().stream()).toList()) {
                assertThat(count("SELECT COUNT(*) FROM watcher_relations WHERE target_user_id = ? OR watcher_user_id = ?",
                        bytes(id), bytes(id))).isZero();
            }
        }

        @Test
        @DisplayName("더미 멤버는 회차와 무관하게 유지 — 다음 생성은 있는 더미를 다시 쓰고 새로 만들지 않는다")
        void reusesDummies() {
            CreateResult first = created();
            long dummies = count("SELECT COUNT(*) FROM users WHERE oauth_subject LIKE 'ruleup-demo-%'");

            CreateResult next = seed.create(1, evaluationEnd());

            assertThat(count("SELECT COUNT(*) FROM users WHERE oauth_subject LIKE 'ruleup-demo-%'")).isEqualTo(dummies);
            assertThat(next.manualRoomId()).as("데모 방은 회차마다 새로").isNotIn(first.manualRoomId(), first.autoRoomId());
            assertThat(next.autoRoomId()).isNotIn(first.manualRoomId(), first.autoRoomId());
        }
    }

    // =====================================================================
    // reset
    // =====================================================================

    @Nested
    @DisplayName("reset — 하나라도 어긋나면 아무것도 바꾸지 않는다")
    class ResetRefuses {

        @Test
        @DisplayName("지난 round 를 확인 인자로 넣으면 실패 — 지난 계정의 상태·동의·참여가 그대로 남는다")
        void confirmRoundMismatch() {
            CreateResult earlier = seed.create(1, evaluationEnd());
            CreateResult current = seed.create(1, evaluationEnd());
            UUID prev = earlier.accountIds().get(0);
            // 지난 심사위원이 남긴 상태 — 개별 동의 · 방 나가기 · 탈퇴
            agree(prev, AgreementType.LOCATION_INFO);
            leave(earlier.manualRoomId(), prev);
            userAccountService.withdraw(prev, CONFIRM_PHRASE);
            Map<String, Object> before = snapshot(earlier, current);

            ReviewAccountPolicy cfg = config(current.round(), current.accountIds(), scopeOf(earlier, current), true, expiry(), clock);
            assertThatThrownBy(() -> seed.reset(scopeOf(earlier, current), cfg, earlier.round()))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.ROUND_MISMATCH));

            assertThat(snapshot(earlier, current)).isEqualTo(before);
            assertThat(statusOf(prev)).as("지난 계정을 되살리지 않는다").isEqualTo("WITHDRAWN");
        }

        @Test
        @DisplayName("만료가 지난 설정(끝난 회차)에서는 실패한다")
        void expiredConfig() {
            CreateResult current = seed.create(1, evaluationEnd());
            agree(current.accountIds().get(0), AgreementType.HEALTH_INFO);
            Map<String, Object> before = snapshot(current);

            Clock afterExpiry = Clock.fixed(expiry().plus(Duration.ofHours(1)), KST);
            ReviewAccountPolicy cfg = config(current.round(), current.accountIds(), scopeOf(current), true, expiry(), afterExpiry);
            assertThatThrownBy(() -> seed.reset(scopeOf(current), cfg, null))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.SWITCHED_OFF));

            assertThat(snapshot(current)).isEqualTo(before);
        }

        @Test
        @DisplayName("스위치가 꺼진 설정에서는 실패한다 — codes 가 있는 채로 꺼져 있으면 종료 스케줄러가 곧 탈퇴시킨다")
        void disabledConfig() {
            CreateResult current = seed.create(1, evaluationEnd());
            agree(current.accountIds().get(0), AgreementType.HEALTH_INFO);
            Map<String, Object> before = snapshot(current);

            ReviewAccountPolicy cfg = config(current.round(), current.accountIds(), scopeOf(current), false, expiry(), clock);
            assertThatThrownBy(() -> seed.reset(scopeOf(current), cfg, null))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.SWITCHED_OFF));

            assertThat(snapshot(current)).isEqualTo(before);
        }

        @Test
        @DisplayName("codes 에 지난 회차 계정이 섞여 있으면 전체 실패 — 이번 회차 계정도 건드리지 않는다")
        void mixedRoundCodes() {
            CreateResult earlier = seed.create(1, evaluationEnd());
            CreateResult current = seed.create(1, evaluationEnd());
            agree(earlier.accountIds().get(0), AgreementType.LOCATION_INFO);
            agree(current.accountIds().get(0), AgreementType.LOCATION_INFO);
            Map<String, Object> before = snapshot(earlier, current);

            List<UUID> codes = List.of(current.accountIds().get(0), earlier.accountIds().get(0));
            ReviewAccountPolicy cfg = config(current.round(), codes, scopeOf(earlier, current), true, expiry(), clock);
            assertThatThrownBy(() -> seed.reset(scopeOf(earlier, current), cfg, current.round()))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.MIXED_ROUND));

            assertThat(snapshot(earlier, current)).isEqualTo(before);
        }

        @Test
        @DisplayName("대상 계정이 없으면 실패한다 — codes 의 계정 행이 하나도 없음")
        void noTarget() {
            CreateResult current = seed.create(1, evaluationEnd());
            UUID missing = UUID.randomUUID();
            Map<String, Object> before = snapshot(current);

            ReviewAccounts scope = new ReviewAccounts(Stream.concat(scopeIds(current).stream(), Stream.of(missing))
                    .map(UUID::toString).collect(Collectors.joining(",")));
            ReviewAccountPolicy cfg = config(current.round(), List.of(missing), scope, true, expiry(), clock);
            assertThatThrownBy(() -> seed.reset(scope, cfg, null))
                    .isInstanceOfSatisfying(ReviewSeedException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.NO_TARGET));

            assertThat(snapshot(current)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("reset — 이번 회차 계정만 시드 상태로")
    class ResetRestores {

        @Test
        @DisplayName("잠금·참여·점수·개별 동의·탈퇴·기기를 되돌리고, RT·더미·일반 유저·다른 회차는 그대로 둔다")
        void restoresSeedState() throws Exception {
            CreateResult other = seed.create(1, evaluationEnd());
            CreateResult current = seed.create(2, evaluationEnd());
            UUID j0 = current.accountIds().get(0);
            UUID j1 = current.accountIds().get(1);
            Map<UUID, List<Object>> scoreAfterCreate = scores(current.accountIds());

            // --- 평가·점검 중에 바뀐 상태 ---
            jdbcTemplate.update("UPDATE users SET profile_changed_at = NOW(6), nickname_changed_at = NOW(6) WHERE id = ?", (Object) bytes(j0));
            agree(j0, AgreementType.LOCATION_INFO);
            agree(j0, AgreementType.HEALTH_INFO);
            jdbcTemplate.update("UPDATE user_score_summaries SET total_score = total_score + 100 WHERE user_id = ?", (Object) bytes(j0));
            leave(current.manualRoomId(), j0);
            jdbcTemplate.update("INSERT INTO DeviceToken (id, userId, token, platform, isActive, lastSeenAt, createdAt) "
                    + "VALUES (?, ?, ?, 'ANDROID', 1, NOW(6), NOW(6))", bytes(UUID.randomUUID()), bytes(j0), "seed-it-" + UUID.randomUUID());
            jdbcTemplate.update("UPDATE users SET device_id = 'seed-it-device' WHERE id = ?", (Object) bytes(j0));
            User j0User = userRepository.findById(j0).orElseThrow();
            tokenService.issueTokenPair(j0User);
            long liveRts = count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", bytes(j0));

            // 실제 유저 — 심사위원이 만든 방에 들어오고, 데모 자동 인증 방에도 들어온다
            Member real = member("seed-real");
            UUID judgeRoom = insertChallenge(j0, "EXERCISE", "ACTIVE", "GROUP");
            insertActiveMembership(judgeRoom, j0, "OWNER");
            insertActiveMembership(judgeRoom, real.id(), "MEMBER");
            insertActiveMembership(current.autoRoomId(), real.id(), "MEMBER");

            userAccountService.withdraw(j1, CONFIRM_PHRASE);   // 심사위원이 탈퇴해 봤다

            long consentEvents = count("SELECT COUNT(*) FROM user_agreement_events WHERE user_id = ?", bytes(j0));
            List<Map<String, Object>> realRows = rows("SELECT * FROM users WHERE id = ?", real.id());
            List<Map<String, Object>> realMemberships = rows("SELECT * FROM challenge_members WHERE user_id = ? ORDER BY id", real.id());
            Map<UUID, List<Map<String, Object>>> dummyRows = new LinkedHashMap<>();
            for (UUID d : current.dummyIds()) dummyRows.put(d, rows("SELECT * FROM users WHERE id = ?", d));
            Map<String, Object> otherRound = snapshot(other);
            Map<UUID, String> subjects = new LinkedHashMap<>();
            for (UUID id : current.accountIds()) subjects.put(id, subjectOf(id));

            // --- 리셋 ---
            ReviewAccountPolicy cfg = config(current.round(), current.accountIds(), scopeOf(current), true, expiry(), clock);
            ResetResult res = seed.reset(scopeOf(current), cfg, current.round());

            assertThat(res.round()).isEqualTo(current.round());
            assertThat(res.accountIds()).containsExactlyElementsOf(current.accountIds());

            // 탈퇴 → 일반 복원과 같게 되살림
            assertThat(statusOf(j1)).isEqualTo("ACTIVE");
            // 닉네임·프로필 변경 잠금 해제
            Map<String, Object> u0 = row("SELECT profile_changed_at, nickname_changed_at, device_id FROM users WHERE id = ?", j0);
            assertThat(u0.get("profile_changed_at")).isNull();
            assertThat(u0.get("nickname_changed_at")).isNull();
            // 개별 동의는 현재 상태만 초기화 — 이력(입증 근거)은 남는다
            assertThat(count("SELECT COUNT(*) FROM user_agreement_states WHERE user_id = ? "
                    + "AND agreement_type IN ('LOCATION_INFO','HEALTH_INFO')", bytes(j0))).isZero();
            assertThat(count("SELECT COUNT(*) FROM user_agreement_events WHERE user_id = ?", bytes(j0))).isEqualTo(consentEvents);
            // 푸시 토큰 행 삭제 · device_id NULL
            assertThat(count("SELECT COUNT(*) FROM DeviceToken WHERE userId = ?", bytes(j0))).isZero();
            assertThat(u0.get("device_id")).isNull();
            // RT 는 건드리지 않는다 — 평가 중에 리셋해도 로그인이 유지된다
            assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", bytes(j0)))
                    .isEqualTo(liveRts);
            // subject 는 고치지 않는다
            for (UUID id : current.accountIds()) assertThat(subjectOf(id)).isEqualTo(subjects.get(id));

            // 참여 — 새로 만든 데모 방 두 개에만 참여 중
            assertThat(Set.of(res.manualRoomId(), res.autoRoomId()))
                    .doesNotContainAnyElementsOf(List.of(current.manualRoomId(), current.autoRoomId()));
            for (UUID id : current.accountIds()) {
                assertThat(activeRoomsOf(id)).as("참여 방 %s", id).containsExactlyInAnyOrder(res.manualRoomId(), res.autoRoomId());
            }
            // 심사 계정만 있던 지난 데모 방은 지운다
            assertThat(count("SELECT COUNT(*) FROM challenges WHERE id = ? AND deleted_at IS NULL", bytes(current.manualRoomId())))
                    .isZero();
            // 실제 유저가 들어온 방은 일반 방으로 남는다 — 심사 계정·더미는 나가기처럼 빠지고 방장은 봇
            for (UUID kept : List.of(current.autoRoomId(), judgeRoom)) {
                Map<String, Object> r = room(kept);
                assertThat(r.get("deleted_at")).as("남는 방 %s", kept).isNull();
                assertThat(r.get("owner_type")).isEqualTo("BOT");
                assertThat(activeMembers(kept).keySet()).containsExactly(real.id());
                assertThat(((Number) r.get("participant_count")).intValue()).isEqualTo(1);
            }
            // 일반 유저 행·참여 행은 그대로
            assertThat(rows("SELECT * FROM users WHERE id = ?", real.id())).isEqualTo(realRows);
            assertThat(rows("SELECT * FROM challenge_members WHERE user_id = ? ORDER BY id", real.id())).isEqualTo(realMemberships);
            // 더미 멤버 행은 그대로
            for (var e : dummyRows.entrySet()) {
                assertThat(rows("SELECT * FROM users WHERE id = ?", e.getKey())).as("더미 %s", e.getKey()).isEqualTo(e.getValue());
            }
            // 다른 회차는 그대로
            assertThat(snapshot(other)).isEqualTo(otherRound);

            // 점수 — 같은 날 리셋이면 생성 직후와 같고, 원장 재생과 맞는다
            assertThat(scores(current.accountIds())).isEqualTo(scoreAfterCreate);
            for (UUID id : current.accountIds()) assertThat(scoreProcessor.verifyUser(id)).isEmpty();
            // 새 데모 방에도 이력이 있다
            for (UUID id : current.accountIds()) {
                assertThat(finalRows(res.autoRoomId(), id)).isNotEmpty();
                assertThat(finalRows(res.manualRoomId(), id)).isNotEmpty();
            }
        }

        @Test
        @DisplayName("확인 인자 없이도 설정의 round 로 돈다")
        void withoutConfirmRound() {
            CreateResult current = seed.create(1, evaluationEnd());
            agree(current.accountIds().get(0), AgreementType.HEALTH_INFO);

            ReviewAccountPolicy cfg = config(current.round(), current.accountIds(), scopeOf(current), true, expiry(), clock);
            ResetResult res = seed.reset(scopeOf(current), cfg, null);

            assertThat(res.round()).isEqualTo(current.round());
            assertThat(count("SELECT COUNT(*) FROM user_agreement_states WHERE user_id = ? AND agreement_type = 'HEALTH_INFO'",
                    bytes(current.accountIds().get(0)))).isZero();
        }

        @Test
        @DisplayName("폐기된 코드의 계정도 대상이다 — codes 의 모든 항목이 이번 회차 계정")
        void revokedCodeAccountIsTarget() {
            CreateResult current = seed.create(2, evaluationEnd());
            UUID revoked = current.accountIds().get(1);
            agree(revoked, AgreementType.LOCATION_INFO);

            String codes = "[" + codeEntry("judge-1", current.accountIds().get(0), false) + ","
                    + codeEntry("judge-2", revoked, true) + "]";
            ReviewAccountPolicy cfg = new ReviewAccountPolicy(scopeOf(current),
                    new ReviewLoginProperties(true, current.round(), expiry().toString(), codes), clock);
            ResetResult res = seed.reset(scopeOf(current), cfg, null);

            assertThat(res.accountIds()).containsExactlyElementsOf(current.accountIds());
            assertThat(count("SELECT COUNT(*) FROM user_agreement_states WHERE user_id = ? AND agreement_type = 'LOCATION_INFO'",
                    bytes(revoked))).isZero();
        }
    }

    // =====================================================================
    // 헬퍼
    // =====================================================================

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    private LocalDate evaluationEnd() {
        return today().plusDays(14);
    }

    /** 평가 마지막 날 다음 날 00:00 KST(5-7 「만료」). */
    private Instant expiry() {
        return evaluationEnd().plusDays(1).atStartOfDay(KST).toInstant();
    }

    private static String codeEntry(String label, UUID userId, boolean revoked) {
        return "{\"label\":\"" + label + "\",\"codeHash\":\"" + ReviewCodes.sha256Hex("SEEDIT" + userId)
                + "\",\"userId\":\"" + userId + "\"" + (revoked ? ",\"revoked\":true" : "") + "}";
    }

    private static ReviewAccountPolicy config(String round, List<UUID> codeAccounts, ReviewAccounts scope,
                                              boolean enabled, Instant expiresAt, Clock clock) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < codeAccounts.size(); i++) entries.add(codeEntry("judge-" + (i + 1), codeAccounts.get(i), false));
        return new ReviewAccountPolicy(scope,
                new ReviewLoginProperties(enabled, round, expiresAt.toString(), "[" + String.join(",", entries) + "]"), clock);
    }

    /** REVIEW_ACCOUNT_IDS — 출력된 심사 계정 + 더미 멤버. */
    private static List<UUID> scopeIds(CreateResult... rounds) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (CreateResult r : rounds) {
            ids.addAll(r.accountIds());
            ids.addAll(r.dummyIds());
        }
        return new ArrayList<>(ids);
    }

    private static ReviewAccounts scopeOf(CreateResult... rounds) {
        return new ReviewAccounts(scopeIds(rounds).stream().map(UUID::toString).collect(Collectors.joining(",")));
    }

    private void agree(UUID userId, AgreementType type) {
        tx.executeWithoutResult(s -> {
            User user = userRepository.findById(userId).orElseThrow();
            agreementService.record(user, type, true, props.client().termsVersions().of(type), Instant.now());
        });
    }

    private void leave(UUID roomId, UUID userId) {
        jdbcTemplate.update("UPDATE challenge_members SET status = 'LEFT', left_type = 'LEAVE', left_at = NOW(6) "
                + "WHERE challenge_id = ? AND user_id = ? AND status = 'ACTIVE'", bytes(roomId), bytes(userId));
    }

    /** 시드 규칙과 무관한 최소 계정 행 — 충돌 상황을 만들 때만 쓴다. */
    private void insertBareUser(UUID id, String subject) {
        tx.executeWithoutResult(s -> {
            User user = User.create(OAuthProvider.GOOGLE, subject, null, "충돌" + uniq("").substring(0, 8), null, List.of("EXERCISE"));
            ReflectionTestUtils.setField(user, "id", id);
            userRepository.saveAndFlush(user);
        });
    }

    private Map<String, Object> room(UUID id) {
        return row("SELECT mode, status, visibility, owner_type, owner_id, template_id, start_date, end_date, deleted_at, "
                + "participant_count, "
                + "JSON_UNQUOTE(JSON_EXTRACT(verification_config, '$.selectedMethod')) AS sel, "
                + "JSON_UNQUOTE(JSON_EXTRACT(verification_config, '$.verificationType')) AS vt, "
                + "CAST(JSON_EXTRACT(params, '$.steps') AS UNSIGNED) AS steps FROM challenges WHERE id = ?", id);
    }

    /** 방의 ACTIVE 멤버 → 역할. */
    private Map<UUID, String> activeMembers(UUID roomId) {
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT user_id, role FROM challenge_members WHERE challenge_id = ? AND status = 'ACTIVE'",
                rs -> { out.put(uuid(rs.getBytes(1)), rs.getString(2)); }, (Object) bytes(roomId));
        return out;
    }

    private List<UUID> activeRoomsOf(UUID userId) {
        return jdbcTemplate.query("SELECT challenge_id FROM challenge_members WHERE user_id = ? AND status = 'ACTIVE'",
                (rs, n) -> uuid(rs.getBytes(1)), (Object) bytes(userId));
    }

    /** 확정된(성공·실패) 판정 행 — 날짜순. */
    private List<Map<String, Object>> finalRows(UUID roomId, UUID userId) {
        return jdbcTemplate.query("SELECT id, targetDate, status, verifiedVia FROM VerificationDaily "
                        + "WHERE challengeId = ? AND userId = ? AND status IN ('SUCCESS','FAILED') ORDER BY targetDate",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("idBytes", rs.getBytes("id"));
                    m.put("targetDate", rs.getDate("targetDate").toLocalDate());
                    m.put("status", rs.getString("status"));
                    m.put("verifiedVia", rs.getString("verifiedVia"));
                    return m;
                }, bytes(roomId), bytes(userId));
    }

    /**
     * 최근 4주 — 확정 행은 오늘-28 ~ 오늘-2 안. 어제·오늘은 확정 가능 시각(D+2 00:00 KST) 전이라 확정 행이 있으면 안 된다.
     * 날짜마다 한 행이고, 4주 중 절반 이상을 채운다.
     */
    private void assertWithinLastFourWeeks(List<Map<String, Object>> rows) {
        assertThat(rows).hasSizeGreaterThanOrEqualTo(14);
        List<LocalDate> dates = rows.stream().map(r -> (LocalDate) r.get("targetDate")).toList();
        assertThat(dates).doesNotHaveDuplicates()
                .allSatisfy(d -> assertThat(d).isBetween(today().minusDays(28), today().minusDays(2)));
    }

    private static long statuses(List<Map<String, Object>> rows, String status, String via) {
        return rows.stream().filter(r -> status.equals(r.get("status")) && (via == null || via.equals(r.get("verifiedVia")))).count();
    }

    /** 계정별 [총점, 실제 티어, 표시 티어]. */
    private Map<UUID, List<Object>> scores(List<UUID> ids) {
        Map<UUID, List<Object>> out = new LinkedHashMap<>();
        for (UUID id : ids) {
            Map<String, Object> s = row("SELECT total_score, actual_tier, display_tier FROM user_score_summaries WHERE user_id = ?", id);
            out.put(id, List.of(((Number) s.get("total_score")).intValue(), s.get("actual_tier"), s.get("display_tier")));
        }
        return out;
    }

    /**
     * 실패한 리셋이 「아무것도 바꾸지 않았다」를 보는 스냅숏 — 회차들의 계정·더미의 행과 그 데모 방.
     */
    private Map<String, Object> snapshot(CreateResult... rounds) {
        List<UUID> users = scopeIds(rounds);
        List<UUID> rooms = new ArrayList<>();
        for (CreateResult r : rounds) {
            rooms.add(r.manualRoomId());
            rooms.add(r.autoRoomId());
        }
        String u = placeholders(users.size());
        String c = placeholders(rooms.size());
        Object[] ub = users.stream().map(ReviewSeedServiceIT::bytes).toArray();
        Object[] cb = rooms.stream().map(ReviewSeedServiceIT::bytes).toArray();

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("users", rows("SELECT * FROM users WHERE id IN (" + u + ") ORDER BY id", ub));
        snap.put("user_information", rows("SELECT * FROM user_information WHERE user_id IN (" + u + ") ORDER BY user_id", ub));
        snap.put("agreement_states", rows("SELECT * FROM user_agreement_states WHERE user_id IN (" + u + ") ORDER BY user_id, agreement_type", ub));
        snap.put("agreement_events", rows("SELECT id FROM user_agreement_events WHERE user_id IN (" + u + ") ORDER BY id", ub));
        snap.put("members", rows("SELECT * FROM challenge_members WHERE user_id IN (" + u + ") ORDER BY id", ub));
        snap.put("rooms", rows("SELECT * FROM challenges WHERE id IN (" + c + ") ORDER BY id", cb));
        snap.put("dailies", rows("SELECT id, status, verifiedVia FROM VerificationDaily WHERE userId IN (" + u + ") ORDER BY id", ub));
        snap.put("appeals", rows("SELECT id FROM verification_appeals WHERE userId IN (" + u + ") ORDER BY id", ub));
        snap.put("scores", rows("SELECT * FROM user_score_summaries WHERE user_id IN (" + u + ") ORDER BY user_id", ub));
        snap.put("ledger", rows("SELECT id FROM score_transactions WHERE user_id IN (" + u + ") ORDER BY id", ub));
        snap.put("device_tokens", rows("SELECT * FROM DeviceToken WHERE userId IN (" + u + ") ORDER BY id", ub));
        snap.put("refresh_tokens", rows("SELECT id, revoked_at FROM refresh_tokens WHERE user_id IN (" + u + ") ORDER BY id", ub));
        snap.put("notifications", rows("SELECT id FROM notifications WHERE user_id IN (" + u + ") ORDER BY id", ub));
        return snap;
    }

    /** 계정·방·이력 테이블의 행 수 — 실패한 생성이 아무것도 남기지 않았는지 본다. */
    private Map<String, Long> tableCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : List.of("users", "user_information", "user_agreement_states", "user_agreement_events",
                "challenges", "challenge_members", "challenge_join_events", "challenge_stats",
                "VerificationDaily", "verification_appeals", "user_score_summaries", "score_transactions")) {
            out.put(t, count("SELECT COUNT(*) FROM " + t));
        }
        return out;
    }

    private String subjectOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT oauth_subject FROM users WHERE id = ?", String.class, (Object) bytes(id));
    }

    private String statusOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, (Object) bytes(id));
    }

    private Instant instant(String sql, UUID... ids) {
        Object[] args = Arrays.stream(ids).map(ReviewSeedServiceIT::bytes).toArray();
        java.sql.Timestamp ts = jdbcTemplate.queryForObject(sql, java.sql.Timestamp.class, args);
        assertThat(ts).isNotNull();
        return ts.toInstant();
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private Map<String, Object> row(String sql, UUID id) {
        List<Map<String, Object>> rows = rows(sql, bytes(id));
        assertThat(rows).as(sql).hasSize(1);
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, UUID id) {
        return rows(sql, new Object[]{bytes(id)});
    }

    /** 행을 비교 가능한 값으로 — binary 는 hex 문자열로 바꾼다(byte[] 는 equals 가 동일성 비교다). 원본 byte[] 는 그대로 둔다. */
    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, n) -> {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object v = rs.getObject(i);
                m.put(md.getColumnLabel(i), v instanceof byte[] b ? HexFormat.of().formatHex(b) : v);
            }
            return m;
        }, args);
    }

    private static LocalDate localDate(Object v) {
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return (LocalDate) v;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static UUID uuid(byte[] b) {
        if (b == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static UUID uuid(String hex) {
        return uuid(HexFormat.of().parseHex(hex));
    }

    private static UUID uuid(Object v) {
        return v instanceof String s ? uuid(s) : uuid((byte[]) v);
    }
}
