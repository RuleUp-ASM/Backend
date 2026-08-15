package com.ruleup.ruleup_backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.agreement.UserAgreementRepository;
import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreement;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.moderation.ModerationRequestRepository;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequest;
import com.ruleup.ruleup_backend.moderation.domain.ModerationRequestStatus;
import com.ruleup.ruleup_backend.moderation.domain.ModerationTarget;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.Gender;
import com.ruleup.ruleup_backend.user.domain.NicknameStatus;
import com.ruleup.ruleup_backend.user.domain.OAuthProvider;
import com.ruleup.ruleup_backend.user.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 가입 플로우 스펙 정합 테스트 (요구사항: 성공 플로우부터 실패 플로우, 막혀야 하는 대상 차단까지).
 * 계약 기준: POST /api/v1/auth/signup — 회원가입 API 명세(2026-08-03) + 회원 정책 §1~§4.
 *
 * 커버 범위
 *  1) 성공: 정상 가입 응답 계약(tier BRONZE·10, PENDING 닉네임, accountStatus ACTIVE) + DB 전 상태
 *     (user_information·user_interests·user_agreements 6종·moderation_requests·user_score_summaries)
 *  2) 생일: 필수·만 14세 미만 차단(BIRTHDATE_UNDERAGE)·형식/미래(BIRTHDATE_INVALID)·경계(만 14세 당일 허용)
 *  3) 성별: 필수(GENDER_REQUIRED)·건너뛰기 시 NON_BINARY 저장
 *  4) 약관: 필수 3종 미동의 차단(REQUIRED_AGREEMENT_MISSING)·선택 3종 미동의 허용(false 이력 기록)
 *  5) 관심사: 0~6개 허용·초과 차단(INTEREST_LIMIT_EXCEEDED)·미정의 코드 차단(CATEGORY_INVALID)
 *  6) 닉네임: 형식(모음만 불허·자음만 허용)·중복(신청 PENDING/승인 닉네임 점유 모두 409)
 *  7) signupToken: 위조/만료/재사용(1회용) 차단(INVALID_SIGNUP_TOKEN)
 *  8) 기기: deviceId·deviceInfo 누락 차단(INVALID_DEVICE_INFO)
 *  9) 동일 설치 다계정 차단: signup·login 신규 분기 양쪽 403 INSTALLATION_ALREADY_REGISTERED
 * 10) 재가입: 동일 (provider, subject) 재가입 시도는 기존 유저 로그인으로 수렴(신규 행 생성 금지)
 * 11) 로그인 신규 분기: nicknameHint 프리필 제공·birthdayHint/genderHint 는 null 고정
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupFlowIT {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired WebApplicationContext wac;
    private final ObjectMapper om = new ObjectMapper();   // 직렬화 전용(빈 아님 — Boot4는 Jackson3 빈)
    @Autowired AppProperties props;
    @Autowired UserRepository userRepository;
    @Autowired UserAgreementRepository agreementRepository;
    @Autowired ModerationRequestRepository moderationRequestRepository;
    @Autowired UserScoreSummaryRepository scoreSummaryRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    // ==================================================================
    // 헬퍼
    // ==================================================================

    /** 테스트마다 유일한 접미사 — MockOAuthClient 는 code → subject 로 그대로 쓴다. */
    private static String uniq() {
        return "sf" + System.nanoTime() + "n" + SEQ.incrementAndGet();
    }

    private Map<String, Object> deviceInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", "ANDROID");
        m.put("osVersion", "14");
        m.put("sdkInt", 34);
        m.put("deviceModel", "SM-S921N");
        m.put("manufacturer", "samsung");
        m.put("lowRam", false);
        m.put("versionName", "1.0.0");
        m.put("versionCode", 100);
        return m;
    }

    private Map<String, Object> loginBody(String code, String installationId, String deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("codeVerifier", "verifier");
        m.put("redirectUri", "kakao://oauth");
        m.put("installationId", installationId);
        m.put("deviceId", deviceId);
        m.put("deviceInfo", deviceInfo());
        return m;
    }

    private MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
    }

    @SuppressWarnings("unchecked")
    private <T> T read(MvcResult res, String path) throws Exception {
        return (T) JsonPath.read(res.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    /** 신규 로그인 → signupToken 발급 (isNewUser=true 확인 포함). */
    private String issueSignupToken(String code, String installationId, String deviceId) throws Exception {
        MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(code, installationId, deviceId));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(res, "$.data.isNewUser")).isTrue();
        return read(res, "$.data.signupToken");
    }

    private Map<String, Object> agreement(boolean agreed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agreed", agreed);
        m.put("version", "1.0");
        return m;
    }

    private Map<String, Object> allAgreements() {
        Map<String, Object> ag = new LinkedHashMap<>();
        ag.put("termsOfService", agreement(true));
        ag.put("privacyPolicy", agreement(true));
        ag.put("locationService", agreement(true));
        ag.put("marketing", agreement(true));
        ag.put("event", agreement(false));
        ag.put("nightPush", agreement(false));
        return ag;
    }

    /** 계약 예시 그대로의 기본 가입 요청 바디. 테스트별로 put/remove 로 변형한다. */
    private Map<String, Object> signupBody(String signupToken, String nickname,
                                           String installationId, String deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signupToken", signupToken);
        m.put("installationId", installationId);
        m.put("nickname", nickname);
        m.put("interestCategories", List.of("EXERCISE", "READING"));
        m.put("birthDate", "2000-05-27");
        m.put("gender", "MALE");
        m.put("agreements", allAgreements());
        m.put("deviceId", deviceId);
        m.put("deviceInfo", deviceInfo());
        return m;
    }

    /** 로그인부터 가입 요청 바디까지 한 번에 준비. */
    private Map<String, Object> preparedSignup(String tag, String nickname) throws Exception {
        String token = issueSignupToken(tag, "inst-" + tag, "dev-" + tag);
        return signupBody(token, nickname, "inst-" + tag, "dev-" + tag);
    }

    private void expectError(MvcResult res, int status, String code) throws Exception {
        assertThat(res.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
        assertThat((Boolean) read(res, "$.success")).isFalse();
        assertThat((String) read(res, "$.error.code")).isEqualTo(code);
    }

    /** 성공 가입 후 유저를 DB에서 찾는다 (subject = mock-kakao-{code}). */
    private User findUser(String code) {
        return userRepository.findByOauthProviderAndOauthSubject(OAuthProvider.KAKAO, "mock-kakao-" + code)
                .orElseThrow();
    }

    // ==================================================================
    // 1) 성공 플로우
    // ==================================================================

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("정상 가입: 응답 계약(브론즈 10점·PENDING 닉네임·ACTIVE) + DB 전 상태가 만들어진다")
        void signup_success_contract_and_db_state() throws Exception {
            String tag = uniq();
            MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, "도전왕" + (SEQ.get() % 1000)));

            // ---- 응답 계약 ----
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isTrue();
            assertThat((String) read(res, "$.data.accessToken")).isNotBlank();
            assertThat((String) read(res, "$.data.refreshToken")).isNotBlank();
            assertThat((String) read(res, "$.data.tokenType")).isEqualTo("Bearer");
            assertThat((Integer) read(res, "$.data.flushIntervalSec")).isPositive();
            assertThat((String) read(res, "$.data.user.nicknameStatus")).isEqualTo("PENDING");
            assertThat((String) read(res, "$.data.user.tier")).isEqualTo("BRONZE");
            assertThat((Integer) read(res, "$.data.user.score")).isEqualTo(10);
            assertThat((String) read(res, "$.data.user.displayTier")).isEqualTo("BRONZE");
            assertThat((Boolean) read(res, "$.data.user.onboardingCompleted")).isTrue();
            assertThat((String) read(res, "$.data.user.accountStatus")).isEqualTo("ACTIVE");
            assertThat((Object) read(res, "$.data.user.profileImageUrl")).isNull();   // 가입 시점엔 항상 null
            assertThat((Object) read(res, "$.data.user.lockInfo")).isNull();

            // ---- DB 상태 ----
            User user = findUser(tag);
            assertThat(user.getBirthDate()).isEqualTo(LocalDate.parse("2000-05-27"));
            assertThat(user.getGender()).isEqualTo(Gender.MALE);
            assertThat(user.getInterestCategories()).containsExactlyInAnyOrder("EXERCISE", "READING");
            assertThat(user.getInstallationId()).isEqualTo("inst-" + tag);
            assertThat(user.getDeviceId()).isEqualTo("dev-" + tag);

            // 임시 승인 닉네임: UUID 뒤 8자리, 신청값과 별개 (타인 화면용)
            assertThat(user.getApprovedNickname()).hasSize(8).isNotEqualTo(user.getNickname());
            assertThat(user.getNicknameStatus()).isIn(NicknameStatus.PENDING, NicknameStatus.APPROVED);

            // 약관 6종 이력 — 필수 3종 true, event/nightPush 는 false 로도 행이 남는다(append-only)
            List<UserAgreement> ags = agreementRepository.findByUser_IdOrderByCreatedAtDescIdDesc(user.getId());
            assertThat(ags).hasSize(6);
            assertThat(ags).filteredOn(a -> a.getAgreementType() == AgreementType.TOS).singleElement()
                    .satisfies(a -> assertThat(a.isAgreed()).isTrue());
            assertThat(ags).filteredOn(a -> a.getAgreementType() == AgreementType.LOCATION).singleElement()
                    .satisfies(a -> assertThat(a.isAgreed()).isTrue());
            assertThat(ags).filteredOn(a -> a.getAgreementType() == AgreementType.EVENT).singleElement()
                    .satisfies(a -> assertThat(a.isAgreed()).isFalse());

            // 닉네임 심사 요청 기록
            List<ModerationRequest> reqs = moderationRequestRepository
                    .findByUserIdAndTarget(user.getId(), ModerationTarget.NICKNAME);
            assertThat(reqs).isNotEmpty();
            assertThat(reqs.getFirst().getContent()).isEqualTo(user.getNickname());
            assertThat(reqs.getFirst().getStatus()).isIn(
                    ModerationRequestStatus.PENDING, ModerationRequestStatus.APPROVED);

            // 시작 티어 브론즈 10점
            var summary = scoreSummaryRepository.findById(user.getId()).orElseThrow();
            assertThat(summary.getTotalScore()).isEqualTo(10);
            assertThat(summary.getActualTier()).isEqualTo(Tier.BRONZE);
            assertThat(summary.getDisplayTier()).isEqualTo(Tier.BRONZE);
        }

        @Test
        @DisplayName("만 14세가 되는 생일 당일이면 가입할 수 있다 (경계값)")
        void signup_exactly_14_years_old_allowed() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "경계값유저" + SEQ.get());
            body.put("birthDate", LocalDate.now(KST).minusYears(14).toString());
            MvcResult res = postJson("/api/v1/auth/signup", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("관심사는 건너뛰기(빈 배열) 가능하다 — 0~6개 허용")
        void signup_empty_interests_allowed() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무관심유저" + SEQ.get());
            body.put("interestCategories", List.of());
            MvcResult res = postJson("/api/v1/auth/signup", body);
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((List<?>) read(res, "$.data.user.interestCategories")).isEmpty();
        }

        @Test
        @DisplayName("성별 건너뛰기 시 클라가 보내는 NON_BINARY 가 그대로 저장된다 (4종 전수는 OnboardingApiContractIT)")
        void signup_non_binary_gender_stored() throws Exception {
            String tag = uniq();
            Map<String, Object> body = preparedSignup(tag, "논바이유저" + SEQ.get());
            body.put("gender", "NON_BINARY");
            assertThat(postJson("/api/v1/auth/signup", body).getResponse().getStatus()).isEqualTo(200);
            assertThat(findUser(tag).getGender()).isEqualTo(Gender.NON_BINARY);
        }

        @Test
        @DisplayName("선택 약관(마케팅·이벤트·야간)은 모두 거부해도 가입된다")
        void signup_optional_agreements_all_false_allowed() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "선택거부" + SEQ.get());
            Map<String, Object> ag = allAgreements();
            ag.put("marketing", agreement(false));
            ag.put("event", agreement(false));
            ag.put("nightPush", agreement(false));
            body.put("agreements", ag);
            assertThat(postJson("/api/v1/auth/signup", body).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ==================================================================
    // 2) 생일 / 성별 검증
    // ==================================================================

    @Nested
    @DisplayName("생일·성별")
    class BirthDateAndGender {

        @Test
        @DisplayName("만 14세 미만은 가입할 수 없다 — 400 BIRTHDATE_UNDERAGE (가드레일: 통과 0건)")
        void underage_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "미성년유저" + SEQ.get());
            body.put("birthDate", LocalDate.now(KST).minusYears(14).plusDays(1).toString());
            expectError(postJson("/api/v1/auth/signup", body), 400, "BIRTHDATE_UNDERAGE");
        }

        @Test
        @DisplayName("생일 누락은 400 BIRTHDATE_INVALID — 생일은 필수 입력")
        void birthdate_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무생일유저" + SEQ.get());
            body.remove("birthDate");
            expectError(postJson("/api/v1/auth/signup", body), 400, "BIRTHDATE_INVALID");
        }

        @Test
        @DisplayName("생일 형식 오류는 400 BIRTHDATE_INVALID")
        void birthdate_malformed_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "형식오류" + SEQ.get());
            body.put("birthDate", "2000-13-99");
            expectError(postJson("/api/v1/auth/signup", body), 400, "BIRTHDATE_INVALID");
        }

        @Test
        @DisplayName("미래 날짜 생일은 400 BIRTHDATE_INVALID")
        void birthdate_future_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "미래인간" + SEQ.get());
            body.put("birthDate", LocalDate.now(KST).plusDays(1).toString());
            expectError(postJson("/api/v1/auth/signup", body), 400, "BIRTHDATE_INVALID");
        }

        @Test
        @DisplayName("gender 필드 누락은 400 GENDER_REQUIRED — API 계약상 필수 필드")
        void gender_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무성별유저" + SEQ.get());
            body.remove("gender");
            expectError(postJson("/api/v1/auth/signup", body), 400, "GENDER_REQUIRED");
        }

        @Test
        @DisplayName("허용 외 gender 값은 400 GENDER_REQUIRED")
        void gender_unknown_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "이상성별" + SEQ.get());
            body.put("gender", "ATTACK_HELICOPTER");
            expectError(postJson("/api/v1/auth/signup", body), 400, "GENDER_REQUIRED");
        }
    }

    // ==================================================================
    // 3) 약관 / 관심사
    // ==================================================================

    @Nested
    @DisplayName("약관·관심사")
    class AgreementsAndInterests {

        @Test
        @DisplayName("필수 약관(위치기반) 미동의는 400 REQUIRED_AGREEMENT_MISSING")
        void required_location_agreement_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "약관거부" + SEQ.get());
            Map<String, Object> ag = allAgreements();
            ag.put("locationService", agreement(false));
            body.put("agreements", ag);
            expectError(postJson("/api/v1/auth/signup", body), 400, "REQUIRED_AGREEMENT_MISSING");
        }

        @Test
        @DisplayName("필수 약관(이용약관) 항목 자체가 빠져도 400 REQUIRED_AGREEMENT_MISSING")
        void required_tos_agreement_absent_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "약관누락" + SEQ.get());
            Map<String, Object> ag = allAgreements();
            ag.remove("termsOfService");
            body.put("agreements", ag);
            expectError(postJson("/api/v1/auth/signup", body), 400, "REQUIRED_AGREEMENT_MISSING");
        }

        @Test
        @DisplayName("agreements 객체 누락은 400 REQUIRED_AGREEMENT_MISSING")
        void agreements_object_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "약관없음" + SEQ.get());
            body.remove("agreements");
            expectError(postJson("/api/v1/auth/signup", body), 400, "REQUIRED_AGREEMENT_MISSING");
        }

        @Test
        @DisplayName("관심사 7개 초과 선택은 400 INTEREST_LIMIT_EXCEEDED")
        void interests_over_limit_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "다관심유저" + SEQ.get());
            body.put("interestCategories", List.of("EXERCISE", "WAKE_SLEEP", "DIET_HEALTH",
                    "STUDY", "READING", "MIND", "FINANCE"));
            expectError(postJson("/api/v1/auth/signup", body), 400, "INTEREST_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("정의되지 않은 관심사 코드는 400 CATEGORY_INVALID")
        void interests_unknown_code_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "오타관심사" + SEQ.get());
            body.put("interestCategories", List.of("EXERCISE", "NOT_A_CATEGORY"));
            expectError(postJson("/api/v1/auth/signup", body), 400, "CATEGORY_INVALID");
        }

        @Test
        @DisplayName("확정된 12종 관심사 enum 전부가 유효하다 (오픈 이슈 #3 확정본)")
        void interests_all_12_codes_valid() throws Exception {
            // 6개씩 두 유저로 나눠 12종 전부 서버가 수용하는지 확인
            Map<String, Object> a = preparedSignup(uniq(), "관심사A" + SEQ.get());
            a.put("interestCategories", List.of("EXERCISE", "WAKE_SLEEP", "DIET_HEALTH", "STUDY", "READING", "MIND"));
            assertThat(postJson("/api/v1/auth/signup", a).getResponse().getStatus()).isEqualTo(200);

            Map<String, Object> b = preparedSignup(uniq(), "관심사B" + SEQ.get());
            b.put("interestCategories", List.of("FINANCE", "HOBBY", "HOUSEKEEPING", "CAREER_PRODUCTIVITY", "DETOX", "ETC"));
            assertThat(postJson("/api/v1/auth/signup", b).getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ==================================================================
    // 4) 닉네임
    // ==================================================================

    @Nested
    @DisplayName("닉네임")
    class Nickname {

        @Test
        @DisplayName("모음만 나열한 닉네임(ㅏㅏㅏ)은 400 — 회원 정책 §3 불허")
        void vowel_only_nickname_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무시됨");
            body.put("nickname", "ㅏㅏㅏ");
            expectError(postJson("/api/v1/auth/signup", body), 400, "NICKNAME_FORMAT_INVALID");
        }

        @Test
        @DisplayName("자음만 나열한 닉네임(ㄱㄱㄱㄱ)은 허용 — 회원 정책 §3")
        void consonant_only_nickname_allowed() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무시됨");
            body.put("nickname", "ㄱㄱㄱㄱ" + (SEQ.get() % 100));
            assertThat(postJson("/api/v1/auth/signup", body).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("특수문자·1자 닉네임은 400 NICKNAME_FORMAT_INVALID")
        void invalid_format_nickname_rejected() throws Exception {
            Map<String, Object> body1 = preparedSignup(uniq(), "무시됨");
            body1.put("nickname", "별명!@#");
            expectError(postJson("/api/v1/auth/signup", body1), 400, "NICKNAME_FORMAT_INVALID");

            Map<String, Object> body2 = preparedSignup(uniq(), "무시됨");
            body2.put("nickname", "한");
            expectError(postJson("/api/v1/auth/signup", body2), 400, "NICKNAME_FORMAT_INVALID");
        }

        @Test
        @DisplayName("타인이 신청 중(PENDING)인 닉네임은 409 NICKNAME_DUPLICATED")
        void duplicated_pending_nickname_rejected() throws Exception {
            String nickname = "겹침" + uniq().substring(2, 10);
            assertThat(postJson("/api/v1/auth/signup", preparedSignup(uniq(), nickname))
                    .getResponse().getStatus()).isEqualTo(200);

            Map<String, Object> body = preparedSignup(uniq(), nickname);
            expectError(postJson("/api/v1/auth/signup", body), 409, "NICKNAME_DUPLICATED");
        }

        @Test
        @DisplayName("타인의 승인된 닉네임도 409 NICKNAME_DUPLICATED")
        void duplicated_approved_nickname_rejected() throws Exception {
            String tag = uniq();
            String nickname = "승인됨" + tag.substring(2, 9);
            assertThat(postJson("/api/v1/auth/signup", preparedSignup(tag, nickname))
                    .getResponse().getStatus()).isEqualTo(200);

            // 승인 상태로 전환 (심사 통과 시뮬레이션)
            User first = findUser(tag);
            first.approveNickname();
            userRepository.save(first);

            Map<String, Object> body = preparedSignup(uniq(), nickname);
            expectError(postJson("/api/v1/auth/signup", body), 409, "NICKNAME_DUPLICATED");
        }
    }

    // ==================================================================
    // 5) signupToken / 기기 검증
    // ==================================================================

    @Nested
    @DisplayName("토큰·기기")
    class TokenAndDevice {

        @Test
        @DisplayName("위조 signupToken 은 400 INVALID_SIGNUP_TOKEN")
        void forged_signup_token_rejected() throws Exception {
            String tag = uniq();
            issueSignupToken(tag, "inst-" + tag, "dev-" + tag);
            Map<String, Object> body = signupBody("this.is.not-a-token", "위조유저" + SEQ.get(),
                    "inst-" + tag, "dev-" + tag);
            expectError(postJson("/api/v1/auth/signup", body), 400, "INVALID_SIGNUP_TOKEN");
        }

        @Test
        @DisplayName("만료된 signupToken 은 400 INVALID_SIGNUP_TOKEN")
        void expired_signup_token_rejected() throws Exception {
            // 서버와 같은 키로 서명하되 이미 만료된 토큰을 직접 만든다
            Instant past = Instant.now().minusSeconds(600);
            String expired = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject("mock-kakao-expired")
                    .claim("type", "SIGNUP")
                    .claim("provider", "KAKAO")
                    .issuedAt(Date.from(past))
                    .expiration(Date.from(past.plusSeconds(1)))
                    .signWith(Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                    .compact();
            String tag = uniq();
            Map<String, Object> body = signupBody(expired, "만료유저" + SEQ.get(), "inst-" + tag, "dev-" + tag);
            expectError(postJson("/api/v1/auth/signup", body), 400, "INVALID_SIGNUP_TOKEN");
        }

        @Test
        @DisplayName("signupToken 은 1회용 — 가입 성공 후 재사용하면 400 INVALID_SIGNUP_TOKEN")
        void reused_signup_token_rejected() throws Exception {
            String tag = uniq();
            Map<String, Object> body = preparedSignup(tag, "일회용유저" + SEQ.get());
            assertThat(postJson("/api/v1/auth/signup", body).getResponse().getStatus()).isEqualTo(200);

            // 같은 토큰으로 다시 (닉네임·설치는 바꿔서 다른 차단 사유 제거)
            String tag2 = uniq();
            Map<String, Object> retry = new LinkedHashMap<>(body);
            retry.put("nickname", "재사용유저" + SEQ.get());
            retry.put("installationId", "inst-" + tag2);
            retry.put("deviceId", "dev-" + tag2);
            expectError(postJson("/api/v1/auth/signup", retry), 400, "INVALID_SIGNUP_TOKEN");
        }

        @Test
        @DisplayName("deviceInfo 누락은 400 INVALID_DEVICE_INFO")
        void device_info_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무기기유저" + SEQ.get());
            body.remove("deviceInfo");
            expectError(postJson("/api/v1/auth/signup", body), 400, "INVALID_DEVICE_INFO");
        }

        @Test
        @DisplayName("deviceId 누락은 400 INVALID_DEVICE_INFO")
        void device_id_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무디바이스" + SEQ.get());
            body.remove("deviceId");
            expectError(postJson("/api/v1/auth/signup", body), 400, "INVALID_DEVICE_INFO");
        }

        @Test
        @DisplayName("installationId 누락은 400 INVALID_REQUEST")
        void installation_id_missing_rejected() throws Exception {
            Map<String, Object> body = preparedSignup(uniq(), "무설치유저" + SEQ.get());
            body.remove("installationId");
            expectError(postJson("/api/v1/auth/signup", body), 400, "INVALID_REQUEST");
        }
    }

    // ==================================================================
    // 6) 막혀야 하는 대상 — 동일 설치 다계정 / 재가입 수렴
    // ==================================================================

    @Nested
    @DisplayName("차단 대상")
    class BlockedCases {

        @Test
        @DisplayName("동일 설치(installationId)에 활성 계정이 있으면 가입은 403 INSTALLATION_ALREADY_REGISTERED")
        void second_account_on_same_installation_rejected_at_signup() throws Exception {
            String tag = uniq();
            assertThat(postJson("/api/v1/auth/signup", preparedSignup(tag, "첫계정" + SEQ.get()))
                    .getResponse().getStatus()).isEqualTo(200);

            // 다른 소셜 계정으로 로그인하되 같은 설치에서 가입 시도
            String tag2 = uniq();
            String token2 = issueSignupToken(tag2, "inst-" + tag2 + "-tmp", "dev-" + tag2);
            Map<String, Object> body = signupBody(token2, "둘째계정" + SEQ.get(), "inst-" + tag, "dev-" + tag);
            expectError(postJson("/api/v1/auth/signup", body), 403, "INSTALLATION_ALREADY_REGISTERED");
        }

        @Test
        @DisplayName("동일 설치의 신규 로그인 분기도 403 INSTALLATION_ALREADY_REGISTERED — 기존 계정 로그인 유도")
        void second_account_on_same_installation_rejected_at_login() throws Exception {
            String tag = uniq();
            assertThat(postJson("/api/v1/auth/signup", preparedSignup(tag, "선점계정" + SEQ.get()))
                    .getResponse().getStatus()).isEqualTo(200);

            // 새 소셜 계정(code 다름)이 같은 설치에서 로그인 → 신규 가입 분기에서 차단
            MvcResult res = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(uniq(), "inst-" + tag, "dev-" + tag));
            expectError(res, 403, "INSTALLATION_ALREADY_REGISTERED");
            // "가입이 안 된다"로 끝내면 사용자가 할 게 없다 — 어느 소셜로 가야 하는지 알려준다
            assertThat((String) read(res, "$.error.reason")).isEqualTo("KAKAO");
        }

        @Test
        @DisplayName("이미 가입된 (provider, subject)의 가입 재시도는 기존 유저 로그인으로 수렴한다 — 중복 행 금지")
        void duplicate_signup_converges_to_login() throws Exception {
            String tag = uniq();
            MvcResult first = postJson("/api/v1/auth/signup", preparedSignup(tag, "원본계정" + SEQ.get()));
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            String firstUserId = read(first, "$.data.user.id");

            long before = userRepository.count();

            // 같은 소셜 계정으로 다시 로그인하면 signupToken 이 아니라 기존 회원 응답이어야 한다
            MvcResult relogin = postJson("/api/v1/auth/oauth/kakao",
                    loginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(relogin, "$.data.isNewUser")).isFalse();
            assertThat((String) read(relogin, "$.data.user.id")).isEqualTo(firstUserId);

            assertThat(userRepository.count()).isEqualTo(before);   // 신규 행 없음
        }
    }

    // ==================================================================
    // 7) 로그인 신규 분기 프리필 계약
    // ==================================================================

    @Nested
    @DisplayName("신규 분기 프리필")
    class NewUserPrefill {

        @Test
        @DisplayName("신규 로그인 응답: nicknameHint 프리필 제공, birthdayHint/genderHint 는 항상 null")
        void new_user_login_prefill_contract() throws Exception {
            String tag = uniq();
            MvcResult res = postJson("/api/v1/auth/oauth/kakao", loginBody(tag, "inst-" + tag, "dev-" + tag));
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) read(res, "$.data.isNewUser")).isTrue();
            assertThat((String) read(res, "$.data.signupToken")).isNotBlank();
            assertThat((Integer) read(res, "$.data.signupTokenExpiresIn")).isPositive();
            assertThat((String) read(res, "$.data.oauthProfile.nicknameHint")).isNotBlank();
            assertThat((Object) read(res, "$.data.oauthProfile.birthdayHint")).isNull();   // 비즈 앱 미전환 — null 고정
            assertThat((Object) read(res, "$.data.oauthProfile.genderHint")).isNull();
        }
    }
}
