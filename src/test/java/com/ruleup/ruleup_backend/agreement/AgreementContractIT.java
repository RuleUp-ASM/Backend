package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

/**
 * 동의 체계 계약 테스트 — 온보딩 테크 스펙 5-2 #11·#12 · 5-3 · 5-7.
 *
 * <p>이 API가 존재해야 하는 이유가 곧 첫 번째 테스트다. 스펙 5-7은 개별 동의 없이 위치·건강
 * 인증을 쓰면 403 {@code AGREEMENT_REQUIRED}를 내린다고 정의해 두고도 제출 엔드포인트가 없어서,
 * 한번 걸리면 사용자가 빠져나올 경로가 없었다. POST /users/me/agreements 가 그 유일한 해소 경로다.
 *
 * <p>커버 범위
 * <ol>
 *   <li>약관 5종 + 개별 동의 2종 = 7종 고정. 구 {@code NIGHT_PUSH} 폐기(2026-08-28)</li>
 *   <li>상태({@code user_agreement_states})와 이력({@code user_agreement_events}) 분리 —
 *       상태는 유저당 최대 7행 고정, 이력은 append-only</li>
 *   <li>둘을 한 트랜잭션에서 같이 쓴다 — 나뉘면 동의는 받았는데 근거가 없거나 그 반대가 된다</li>
 *   <li>필수 약관 철회 금지 · 버전 불일치 거부 · 재동의 판정</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgreementContractIT extends AuthApiSupport {

    private static final String PATH = "/api/v1/users/me/agreements";

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;
    @Autowired UserAgreementEventRepository eventRepository;
    @Autowired UserAgreementStateRepository stateRepository;
    @Autowired AppProperties props;

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

    private MvcResult getAgreements(String accessToken) throws Exception {
        var req = get(PATH);
        if (accessToken != null) req = req.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(req).andReturn();
    }

    private static Map<String, Object> item(String type, boolean agreed, String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("agreed", agreed);
        m.put("version", version);
        return m;
    }

    private static Map<String, Object> body(Map<String, Object>... items) {
        return Map.of("agreements", List.of(items));
    }

    /** 가입 → accessToken. 태그는 테스트마다 유일하다. */
    private String join(String nickname) throws Exception {
        return read(signup(uniq("ag"), nickname + seq()), "$.data.accessToken");
    }

    private UUID userIdOf(String accessToken) throws Exception {
        String id = read(getAgreementsOwnerProbe(accessToken), "$.data.user.id");
        return UUID.fromString(id);
    }

    private MvcResult getAgreementsOwnerProbe(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    /** 응답 배열에서 type 이 일치하는 항목 하나. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pick(MvcResult res, String type) throws Exception {
        List<Map<String, Object>> all = read(res, "$.data.agreements");
        return all.stream().filter(a -> type.equals(a.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("응답에 " + type + " 항목이 없다: " + all));
    }

    // =====================================================================
    @Nested
    @DisplayName("GET /users/me/agreements — 동의 상태 조회")
    class Status {

        @Test
        @DisplayName("약관 5종과 개별 동의 2종을 합쳐 항상 7종을 내린다 — NIGHT_PUSH 는 폐기됐다")
        void returns_seven_types() throws Exception {
            String at = join("동의조회");
            MvcResult res = getAgreements(at);

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            List<Map<String, Object>> all = read(res, "$.data.agreements");
            assertThat(all).extracting(a -> (String) a.get("type"))
                    .containsExactlyInAnyOrder("TOS", "PRIVACY", "LOCATION", "MARKETING", "EVENT",
                            "LOCATION_INFO", "HEALTH_INFO")
                    .doesNotContain("NIGHT_PUSH");
        }

        @Test
        @DisplayName("required 는 가입 필수 여부다 — 필수 3종만 true, 개별 동의 2종은 false")
        void required_flag_means_signup_required() throws Exception {
            String at = join("필수표시");
            MvcResult res = getAgreements(at);

            for (String t : List.of("TOS", "PRIVACY", "LOCATION")) {
                assertThat((Boolean) pick(res, t).get("required")).as(t).isTrue();
            }
            // 개별 동의는 가입 필수가 아니다. 해당 인증 수단을 쓸 때만 필수가 된다.
            for (String t : List.of("MARKETING", "EVENT", "LOCATION_INFO", "HEALTH_INFO")) {
                assertThat((Boolean) pick(res, t).get("required")).as(t).isFalse();
            }
        }

        @Test
        @DisplayName("한 번도 동의한 적 없으면 agreed=false 이고 version·agreedAt 이 모두 null 이다")
        void never_agreed_has_null_version() throws Exception {
            String at = join("미동의");
            MvcResult res = getAgreements(at);

            // 개별 동의 2종은 가입 트랜잭션에서 기록하지 않는다 — 인증 수단 최초 사용 시점에 받는다.
            for (String t : List.of("LOCATION_INFO", "HEALTH_INFO")) {
                Map<String, Object> a = pick(res, t);
                assertThat((Boolean) a.get("agreed")).as(t).isFalse();
                assertThat(a.get("version")).as(t + " version").isNull();
                assertThat(a.get("agreedAt")).as(t + " agreedAt").isNull();
            }
        }

        @Test
        @DisplayName("가입 직후에는 현행 버전으로 동의했으므로 reconsentRequired 가 비어 있다")
        void no_reconsent_right_after_signup() throws Exception {
            String at = join("재동의없음");
            List<String> reconsent = read(getAgreements(at), "$.data.reconsentRequired");
            assertThat(reconsent).isEmpty();
        }

        @Test
        @DisplayName("저장 버전이 현행과 다른 필수 약관만 reconsentRequired 에 담긴다")
        void reconsent_lists_outdated_required_terms() throws Exception {
            String at = join("재동의필요");
            UUID userId = userIdOf(at);

            // 서버가 아는 현행 버전보다 낮은 값으로 상태를 되돌린다(= 약관이 개정된 상황).
            stateRepository.findById(new UserAgreementState.Key(userId, AgreementType.TOS))
                    .ifPresent(s -> stateRepository.save(s.withVersion("0.9")));

            List<String> reconsent = read(getAgreements(at), "$.data.reconsentRequired");
            assertThat(reconsent).containsExactly("TOS");
        }

        @Test
        @DisplayName("선택 약관은 버전이 달라도 재동의 대상이 아니다 — 필수만 화면을 막는다")
        void optional_terms_never_force_reconsent() throws Exception {
            String at = join("선택약관");
            UUID userId = userIdOf(at);

            stateRepository.findById(new UserAgreementState.Key(userId, AgreementType.MARKETING))
                    .ifPresent(s -> stateRepository.save(s.withVersion("0.9")));

            List<String> reconsent = read(getAgreements(at), "$.data.reconsentRequired");
            assertThat(reconsent).doesNotContain("MARKETING");
        }

        @Test
        @DisplayName("미인증이면 401 LOGIN_REQUIRED")
        void unauthenticated_401() throws Exception {
            expectError(getAgreements(null), 401, "LOGIN_REQUIRED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("POST /users/me/agreements — 동의 제출·철회")
    class Submit {

        @Test
        @DisplayName("개별 동의를 제출하면 상태가 켜지고 이력이 한 행 쌓인다 — 403 AGREEMENT_REQUIRED 의 해소 경로")
        void individual_consent_submit() throws Exception {
            String at = join("개별동의");
            UUID userId = userIdOf(at);
            String v = props.client().termsVersions().locationInfo();

            long eventsBefore = eventRepository.countByUser_Id(userId);

            MvcResult res = postJsonAuth(PATH, at, body(item("LOCATION_INFO", true, v)));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            // 응답은 갱신된 항목만 내린다.
            List<Map<String, Object>> updated = read(res, "$.data.agreements");
            assertThat(updated).hasSize(1);
            assertThat(updated.getFirst().get("type")).isEqualTo("LOCATION_INFO");
            assertThat((Boolean) updated.getFirst().get("agreed")).isTrue();
            assertThat(updated.getFirst().get("agreedAt")).isNotNull();

            // 상태 UPSERT + 이력 INSERT 가 같은 트랜잭션에서 함께 일어난다.
            var state = stateRepository
                    .findById(new UserAgreementState.Key(userId, AgreementType.LOCATION_INFO)).orElseThrow();
            assertThat(state.isAgreed()).isTrue();
            assertThat(state.getVersion()).isEqualTo(v);
            assertThat(eventRepository.countByUser_Id(userId)).isEqualTo(eventsBefore + 1);
        }

        @Test
        @DisplayName("여러 항목을 한 번에 처리한다 — 개정 약관이 동시에 여럿 나올 수 있다")
        void batch_submit() throws Exception {
            String at = join("일괄동의");
            var v = props.client().termsVersions();

            MvcResult res = postJsonAuth(PATH, at, body(
                    item("LOCATION_INFO", true, v.locationInfo()),
                    item("HEALTH_INFO", true, v.healthInfo()),
                    item("MARKETING", false, v.marketing())));

            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(res, "$.data.agreements")).hasSize(3);
            assertThat((Boolean) pick(res, "MARKETING").get("agreed")).isFalse();
        }

        @Test
        @DisplayName("철회하면 agreed 만 꺼지고 동의했던 버전은 남는다 — 한 번도 동의 안 한 것과 구분된다")
        void revoke_keeps_version() throws Exception {
            String at = join("철회");
            UUID userId = userIdOf(at);
            String v = props.client().termsVersions().marketing();

            postJsonAuth(PATH, at, body(item("MARKETING", true, v)));
            postJsonAuth(PATH, at, body(item("MARKETING", false, v)));

            Map<String, Object> after = pick(getAgreements(at), "MARKETING");
            assertThat((Boolean) after.get("agreed")).isFalse();
            assertThat(after.get("version")).isEqualTo(v);   // null 이면 "미동의"와 섞여 버린다
        }

        @Test
        @DisplayName("동의와 철회를 반복해도 상태는 7행 고정이고 이력만 쌓인다")
        void states_stay_seven_rows() throws Exception {
            String at = join("토글반복");
            UUID userId = userIdOf(at);
            String v = props.client().termsVersions().marketing();

            long eventsBefore = eventRepository.countByUser_Id(userId);
            for (int i = 0; i < 4; i++) {
                postJsonAuth(PATH, at, body(item("MARKETING", i % 2 == 0, v)));
            }

            assertThat(stateRepository.countByUserId(userId))
                    .as("상태 테이블은 유저당 최대 7행 고정 — PK 조회 한 번으로 끝나야 한다")
                    .isLessThanOrEqualTo(7);
            assertThat(eventRepository.countByUser_Id(userId))
                    .as("이력은 append-only — 정보통신망법상 철회 이력이 남아야 한다")
                    .isEqualTo(eventsBefore + 4);
        }

        @Test
        @DisplayName("필수 약관 3종은 철회할 수 없다 — 400 AGREEMENT_REVOKE_FORBIDDEN")
        void required_terms_cannot_be_revoked() throws Exception {
            String at = join("필수철회");
            var v = props.client().termsVersions();

            expectError(postJsonAuth(PATH, at, body(item("TOS", false, v.termsOfService()))),
                    400, "AGREEMENT_REVOKE_FORBIDDEN");
            expectError(postJsonAuth(PATH, at, body(item("PRIVACY", false, v.privacyPolicy()))),
                    400, "AGREEMENT_REVOKE_FORBIDDEN");
            expectError(postJsonAuth(PATH, at, body(item("LOCATION", false, v.locationService()))),
                    400, "AGREEMENT_REVOKE_FORBIDDEN");
        }

        @Test
        @DisplayName("현재 유효 버전이 아니면 400 AGREEMENT_VERSION_MISMATCH — 구 버전을 동의본으로 남기면 입증이 깨진다")
        void version_must_match_current() throws Exception {
            String at = join("버전불일치");
            expectError(postJsonAuth(PATH, at, body(item("MARKETING", true, "0.1"))),
                    400, "AGREEMENT_VERSION_MISMATCH");
        }

        @Test
        @DisplayName("하나라도 실패하면 전부 롤백된다 — 배열 전체가 한 트랜잭션이다")
        void batch_is_atomic() throws Exception {
            String at = join("원자성");
            UUID userId = userIdOf(at);
            var v = props.client().termsVersions();
            long eventsBefore = eventRepository.countByUser_Id(userId);

            // 첫 항목은 정상, 두 번째가 버전 불일치.
            expectError(postJsonAuth(PATH, at, body(
                    item("LOCATION_INFO", true, v.locationInfo()),
                    item("HEALTH_INFO", true, "0.1"))), 400, "AGREEMENT_VERSION_MISMATCH");

            assertThat(stateRepository
                    .findById(new UserAgreementState.Key(userId, AgreementType.LOCATION_INFO)))
                    .as("앞 항목까지 함께 롤백돼야 한다").isEmpty();
            assertThat(eventRepository.countByUser_Id(userId)).isEqualTo(eventsBefore);
        }

        @Test
        @DisplayName("빈 배열이나 알 수 없는 type 은 400 INVALID_REQUEST")
        void invalid_request() throws Exception {
            String at = join("잘못된요청");

            expectError(postJsonAuth(PATH, at, Map.of("agreements", List.of())),
                    400, "INVALID_REQUEST");
            expectError(postJsonAuth(PATH, at, body(item("NIGHT_PUSH", true, "1.0"))),
                    400, "INVALID_REQUEST");
        }

        @Test
        @DisplayName("미인증이면 401 LOGIN_REQUIRED")
        void unauthenticated_401() throws Exception {
            var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post(PATH)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(body(item("MARKETING", true, "1.0"))))).andReturn();
            expectError(res, 401, "LOGIN_REQUIRED");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("가입 트랜잭션 — 약관 5종")
    class Signup {

        @Test
        @DisplayName("가입은 약관 5종만 기록한다 — 개별 동의 2종은 인증 수단 최초 사용 시점에 받는다")
        void signup_records_five_terms_only() throws Exception {
            String at = join("가입기록");
            UUID userId = userIdOf(at);

            assertThat(eventRepository.countByUser_Id(userId))
                    .as("TOS·PRIVACY·LOCATION·MARKETING·EVENT — 미동의도 false 행으로 남긴다")
                    .isEqualTo(5);
            assertThat(stateRepository.countByUserId(userId)).isEqualTo(5);
        }

        @Test
        @DisplayName("가입 시점에 상태와 이력이 함께 쓰인다 — 하나만 있으면 게이트가 어긋난다")
        void signup_writes_state_and_event_together() throws Exception {
            String at = join("가입정합");
            UUID userId = userIdOf(at);

            for (AgreementType t : List.of(AgreementType.TOS, AgreementType.PRIVACY,
                    AgreementType.LOCATION, AgreementType.MARKETING, AgreementType.EVENT)) {
                assertThat(stateRepository.findById(new UserAgreementState.Key(userId, t)))
                        .as(t.name() + " 상태").isPresent();
                assertThat(eventRepository.findByUser_IdAndAgreementTypeOrderByCreatedAtDesc(userId, t))
                        .as(t.name() + " 이력").isNotEmpty();
            }
        }

        @Test
        @DisplayName("탈퇴해도 동의 이력은 지우지 않는다 — 입증 책임의 근거다")
        void withdraw_keeps_agreement_history() throws Exception {
            String at = join("탈퇴보존");
            UUID userId = userIdOf(at);
            long before = eventRepository.countByUser_Id(userId);

            MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/v1/users/me")
                    .header("Authorization", "Bearer " + at)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(OM.writeValueAsString(Map.of("confirmPhrase", "탈퇴할게요")))).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);

            assertThat(eventRepository.countByUser_Id(userId)).isEqualTo(before);
            User user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getDeletedAt()).isNotNull();
        }
    }
}
