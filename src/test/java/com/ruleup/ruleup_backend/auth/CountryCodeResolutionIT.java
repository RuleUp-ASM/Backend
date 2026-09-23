package com.ruleup.ruleup_backend.auth;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * users.country_code 가 실제로 채워지는지 검증한다.
 *
 * <p>배경 — 운영 DB에서 country_code 가 NULL 로 남아 있었다. 원인은 해석 소스 세 개가 동시에 비어 있었기 때문이다.
 * <ol>
 *   <li>지오 헤더(CloudFront-Viewer-Country 등) — ALB 직결 배포라 아무도 붙여주지 않는다.</li>
 *   <li>{@code deviceInfo.country} — 계약상 선택 필드라 클라이언트가 보내지 않았다.</li>
 *   <li>{@code Accept-Language} — OkHttp/Retrofit 은 이 헤더를 기본으로 붙이지 않는다.</li>
 * </ol>
 * 그래서 (1) 기기 타임존을 폴백 소스로 추가하고 (2) 그래도 해석이 안 되면 서비스 기본 국가로 채워
 * <b>컬럼이 NULL 로 남지 않게</b> 한다. 국가는 유저 입력이 아니라 서버 해석값이므로 여기서 결론이 나야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CountryCodeResolutionIT extends AuthApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Override
    protected MockMvc mvc() { return mvc; }

    // ===== 헬퍼 =====

    /** deviceInfo 에 키를 얹은 가입 바디. value 가 null 이면 키를 넣지 않는다(미전송 재현). */
    private Map<String, Object> signupWithDevice(String tag, String nickname,
                                                 String country, String timeZone) throws Exception {
        Map<String, Object> body = preparedSignup(tag, nickname);
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) body.get("deviceInfo");
        if (country != null) device.put("country", country);
        if (timeZone != null) device.put("timeZone", timeZone);
        return body;
    }

    private User signedUpUser(MvcResult res) throws Exception {
        String userId = read(res, "$.data.user.id");
        return userRepository.findById(UUID.fromString(userId)).orElseThrow();
    }

    private MvcResult postJsonWithHeader(String url, Map<String, Object> body,
                                         String header, String value) throws Exception {
        return mvc.perform(post(url).header(header, value)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OM.writeValueAsString(body))).andReturn();
    }

    // ===== 회귀 가드 =====

    @Test
    @DisplayName("[회귀] 지오 헤더·기기 지역·Accept-Language 가 모두 없어도 country_code 는 NULL 로 남지 않는다")
    void countryCodeIsNeverNullEvenWithNoSignal() throws Exception {
        String tag = uniq("cc-none");
        // 실제 안드 계약 그대로: country/timeZone 미전송, 지오 헤더 없음, Accept-Language 없음.
        MvcResult res = postJson("/api/v1/auth/signup", preparedSignup(tag, "국가없음" + seq()));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        User user = signedUpUser(res);
        assertThat(user.getCountryCode())
                .as("해석 소스가 전부 비어도 서비스 기본 국가로 채워져야 한다")
                .isEqualTo("KR");
    }

    @Test
    @DisplayName("기기 타임존만 보내도 국가가 해석된다 — 안드 기기는 타임존을 항상 들고 있다")
    void resolvesFromDeviceTimeZone() throws Exception {
        String tag = uniq("cc-tz");
        MvcResult res = postJson("/api/v1/auth/signup",
                signupWithDevice(tag, "타임존" + seq(), null, "Asia/Tokyo"));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        assertThat(signedUpUser(res).getCountryCode()).isEqualTo("JP");
    }

    @Test
    @DisplayName("기기 지역(deviceInfo.country)이 타임존보다 우선한다")
    void deviceCountryWinsOverTimeZone() throws Exception {
        String tag = uniq("cc-dev");
        MvcResult res = postJson("/api/v1/auth/signup",
                signupWithDevice(tag, "기기지역" + seq(), "us", "Asia/Seoul"));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        assertThat(signedUpUser(res).getCountryCode()).isEqualTo("US");   // 소문자 정규화 포함
    }

    @Test
    @DisplayName("CDN 지오 헤더가 기기 값보다 우선한다 — 실제 접속 국가가 가장 정확하다")
    void geoHeaderWinsOverDeviceValues() throws Exception {
        String tag = uniq("cc-geo");
        Map<String, Object> body = signupWithDevice(tag, "지오헤더" + seq(), "US", "Asia/Seoul");
        MvcResult res = postJsonWithHeader("/api/v1/auth/signup", body, "CF-IPCountry", "DE");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        assertThat(signedUpUser(res).getCountryCode()).isEqualTo("DE");
    }

    @Test
    @DisplayName("로그인마다 국가가 최신화된다 — 기본값으로 가입한 유저도 타임존이 오면 교정된다")
    void loginRefreshesCountryCode() throws Exception {
        String tag = uniq("cc-login");
        MvcResult signup = postJson("/api/v1/auth/signup", preparedSignup(tag, "재로그인" + seq()));
        assertThat(signup.getResponse().getStatus()).isEqualTo(200);
        UUID userId = UUID.fromString(read(signup, "$.data.user.id"));
        assertThat(userRepository.findById(userId).orElseThrow().getCountryCode()).isEqualTo("KR");

        Map<String, Object> login = loginBody(tag, "inst-" + tag, "dev-" + tag);
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) login.get("deviceInfo");
        device.put("timeZone", "Asia/Tokyo");

        MvcResult res = postJson("/api/v1/auth/oauth/kakao", login);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) read(res, "$.data.isNewUser")).isFalse();

        assertThat(userRepository.findById(userId).orElseThrow().getCountryCode()).isEqualTo("JP");
    }

    @Test
    @DisplayName("해석할 수 없는 값이 와도 기존 국가를 지우지 않는다")
    void unresolvableValueDoesNotWipeExistingCountry() throws Exception {
        String tag = uniq("cc-keep");
        MvcResult signup = postJson("/api/v1/auth/signup",
                signupWithDevice(tag, "유지" + seq(), "JP", null));
        UUID userId = UUID.fromString(read(signup, "$.data.user.id"));
        assertThat(userRepository.findById(userId).orElseThrow().getCountryCode()).isEqualTo("JP");

        Map<String, Object> login = loginBody(tag, "inst-" + tag, "dev-" + tag);
        @SuppressWarnings("unchecked")
        Map<String, Object> device = (Map<String, Object>) login.get("deviceInfo");
        device.put("country", "ZZ1");            // 형식 오류
        device.put("timeZone", "not/a-zone");    // 해석 불가

        MvcResult res = postJson("/api/v1/auth/oauth/kakao", login);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(userRepository.findById(userId).orElseThrow().getCountryCode())
                .as("해석 실패는 기존 값을 덮어쓰지 않는다(기본값으로 되돌리지도 않는다)")
                .isEqualTo("JP");
    }
}
