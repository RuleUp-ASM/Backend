package com.ruleup.ruleup_backend.admin.access;

import com.ruleup.ruleup_backend.admin.access.CloudflareAccessVerifier.Failure;
import com.ruleup.ruleup_backend.admin.access.CloudflareAccessVerifier.VerificationException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access JWT 검증 — 헤더가 있다는 사실도, 그 안의 이메일도 믿지 않는다.
 * 서명·발급자·대상·만료 가운데 하나라도 어긋나면 신원을 내주지 않는다.
 */
class CloudflareAccessVerifierTest {

    private final AccessTokens issuer = new AccessTokens("kid-1");
    private final AtomicInteger fetches = new AtomicInteger();
    private final AdminAccessProperties props = new AdminAccessProperties(
            AccessTokens.TEAM, List.of(AccessTokens.AUD), List.of("ops@ruleup.co.kr"), List.of(), 3600L);
    private final CloudflareAccessVerifier verifier = new CloudflareAccessVerifier(props, () -> {
        fetches.incrementAndGet();
        return issuer.jwks();
    }, Clock.systemUTC());

    private Failure failureOf(String token) {
        try {
            verifier.verify(token);
        } catch (VerificationException e) {
            return e.failure();
        }
        throw new AssertionError("거부돼야 하는 토큰이 통과했다");
    }

    @Test
    @DisplayName("정상 토큰은 소문자 이메일로 신원을 낸다")
    void valid() {
        var identity = verifier.verify(issuer.issue("Ops@RuleUp.co.kr"));
        assertThat(identity.email()).isEqualTo("ops@ruleup.co.kr");
    }

    @Test
    @DisplayName("헤더가 없거나 형식이 깨지면 거부")
    void missing_or_malformed() {
        assertThat(failureOf(null)).isEqualTo(Failure.MISSING);
        assertThat(failureOf("")).isEqualTo(Failure.MISSING);
        assertThat(failureOf("not-a-jwt")).isEqualTo(Failure.MALFORMED);
    }

    @Test
    @DisplayName("다른 키로 서명한 위조 토큰 — 같은 kid 를 달아도 거부")
    void forged_signature() {
        AccessTokens attacker = new AccessTokens("kid-1");
        assertThat(failureOf(attacker.issue("ops@ruleup.co.kr"))).isEqualTo(Failure.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("모르는 kid 는 공개키를 한 번 다시 받아 보고, 그래도 없으면 거부")
    void unknown_kid() {
        verifier.verify(issuer.issue("ops@ruleup.co.kr"));
        int before = fetches.get();
        AccessTokens other = new AccessTokens("kid-unknown");
        assertThat(failureOf(other.issue("ops@ruleup.co.kr"))).isEqualTo(Failure.UNKNOWN_KEY);
        // 위조 토큰을 쏟아부어도 재조회는 최소 간격으로 묶인다
        failureOf(other.issue("ops@ruleup.co.kr"));
        failureOf(other.issue("ops@ruleup.co.kr"));
        assertThat(fetches.get() - before).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("HS256 등 RS256 이 아닌 알고리즘은 거부 — 공개키를 HMAC 비밀로 쓰는 혼동 공격 차단")
    void wrong_algorithm() {
        String hs = Jwts.builder().header().keyId("kid-1").and()
                .issuer(AccessTokens.TEAM).audience().add(AccessTokens.AUD).and()
                .claim("email", "ops@ruleup.co.kr")
                .expiration(java.util.Date.from(Instant.now().plusSeconds(600)))
                .signWith(Jwts.SIG.HS256.key().build())
                .compact();
        assertThat(failureOf(hs)).isEqualTo(Failure.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("서명 없는(alg=none) 토큰은 거부")
    void unsigned() {
        String header = b64("{\"alg\":\"none\",\"kid\":\"kid-1\"}");
        String body = b64("{\"iss\":\"" + AccessTokens.TEAM + "\",\"aud\":[\"" + AccessTokens.AUD
                + "\"],\"email\":\"ops@ruleup.co.kr\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        assertThat(failureOf(header + "." + body + ".")).isIn(Failure.MALFORMED, Failure.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("다른 팀이 발급한 토큰은 거부")
    void wrong_issuer() {
        String token = issuer.issue(Map.of("email", "ops@ruleup.co.kr"),
                "https://evil.cloudflareaccess.com", AccessTokens.AUD, Instant.now().plusSeconds(600));
        assertThat(failureOf(token)).isEqualTo(Failure.WRONG_ISSUER);
    }

    @Test
    @DisplayName("같은 팀의 다른 Access 앱(aud) 토큰은 거부")
    void wrong_audience() {
        String token = issuer.issue(Map.of("email", "ops@ruleup.co.kr"),
                AccessTokens.TEAM, "aud-some-other-app", Instant.now().plusSeconds(600));
        assertThat(failureOf(token)).isEqualTo(Failure.WRONG_AUDIENCE);
    }

    @Test
    @DisplayName("만료된 토큰은 거부(시계 오차 30초 밖)")
    void expired() {
        String token = issuer.issue(Map.of("email", "ops@ruleup.co.kr"),
                AccessTokens.TEAM, AccessTokens.AUD, Instant.now().minusSeconds(120));
        assertThat(failureOf(token)).isEqualTo(Failure.EXPIRED);
    }

    @Test
    @DisplayName("이메일 없는 서비스 토큰은 거부 — 관리자 기능은 사람만 쓴다")
    void service_token_without_email() {
        String token = issuer.issue(Map.of("common_name", "svc.ruleup"),
                AccessTokens.TEAM, AccessTokens.AUD, Instant.now().plusSeconds(600));
        assertThat(failureOf(token)).isEqualTo(Failure.NO_EMAIL);
    }

    @Test
    @DisplayName("설정이 비면 isConfigured=false — 빈 허용 목록을 전원 허용으로 읽지 않는다")
    void empty_config_is_closed() {
        assertThat(new AdminAccessProperties("", null, null, null, null).isConfigured()).isFalse();
        assertThat(new AdminAccessProperties(AccessTokens.TEAM, List.of(AccessTokens.AUD), List.of(" "), null, null)
                .isConfigured()).isFalse();
        assertThatThrownBy(() -> verifier.verify(" ")).isInstanceOf(VerificationException.class);
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
