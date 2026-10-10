package com.ruleup.ruleup_backend.admin.access;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import lombok.extern.slf4j.Slf4j;

import java.security.Key;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cloudflare Access 가 원본으로 보내는 {@code Cf-Access-Jwt-Assertion} 을 검증한다.
 *
 * <p>헤더가 있다는 사실도, 그 안의 이메일도 믿지 않는다. 확인하는 것은 넷이다.
 * <ol>
 *   <li><b>서명</b> — 팀 도메인이 공개한 RSA 키(RS256)로만. 다른 알고리즘·모르는 kid 는 거부</li>
 *   <li><b>발급자</b>(iss) — 설정한 팀 도메인과 정확히 일치</li>
 *   <li><b>대상</b>(aud) — 이 관리자 애플리케이션의 AUD 태그 중 하나</li>
 *   <li><b>만료</b>(exp·nbf) — 시계 오차 30초까지만</li>
 * </ol>
 * 그다음 이메일을 꺼낸다. 서비스 토큰(기계 신원)에는 이메일이 없으므로 여기서 떨어진다 —
 * 관리자 기능은 사람만 쓴다.
 *
 * <p>공개키는 캐시한다. Cloudflare 는 키를 주기적으로 돌리므로 <b>모르는 kid 가 오면</b> 캐시
 * 수명과 상관없이 다시 받는다. 다만 위조 토큰을 쏟아부어 매번 다시 받게 만들 수 없도록 재조회는
 * 10초에 한 번으로 묶는다.
 */
@Slf4j
public class CloudflareAccessVerifier {

    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(10);
    private static final String ALGORITHM = "RS256";

    /** 검증 실패 사유. 응답에는 싣지 않고 로그에만 남긴다 — 실패 이유도 공격자에게는 정보다. */
    public enum Failure { MISSING, MALFORMED, BAD_SIGNATURE, UNKNOWN_KEY, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED, NO_EMAIL }

    public static final class VerificationException extends RuntimeException {
        private final Failure failure;

        VerificationException(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** 검증을 통과한 신원. {@code subject} 는 IdP 와 무관한 Access 사용자 id 다. */
    public record AccessIdentity(String email, String subject) {}

    private final AdminAccessProperties properties;
    private final AccessKeySource keySource;
    private final Clock clock;

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public CloudflareAccessVerifier(AdminAccessProperties properties, AccessKeySource keySource, Clock clock) {
        this.properties = properties;
        this.keySource = keySource;
        this.clock = clock;
    }

    public AccessIdentity verify(String token) {
        if (token == null || token.isBlank()) throw new VerificationException(Failure.MISSING);

        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            if (!ALGORITHM.equals(header.getAlgorithm()))
                                throw new VerificationException(Failure.BAD_SIGNATURE);
                            return keyFor(header.getKeyId());
                        }
                    })
                    .requireIssuer(properties.teamDomain())
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .clock(() -> java.util.Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (VerificationException e) {
            throw e;
        } catch (io.jsonwebtoken.ExpiredJwtException | io.jsonwebtoken.PrematureJwtException e) {
            throw new VerificationException(Failure.EXPIRED);
        } catch (io.jsonwebtoken.IncorrectClaimException | io.jsonwebtoken.MissingClaimException e) {
            throw new VerificationException(Failure.WRONG_ISSUER);
        } catch (io.jsonwebtoken.security.SecurityException e) {
            throw new VerificationException(Failure.BAD_SIGNATURE);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            throw new VerificationException(Failure.MALFORMED);
        }

        // exp 가 아예 없는 토큰은 jjwt 가 통과시킨다 — Access 토큰은 늘 exp 를 단다.
        if (claims.getExpiration() == null) throw new VerificationException(Failure.EXPIRED);

        Set<String> aud = claims.getAudience();
        if (aud == null || aud.stream().noneMatch(properties.audiences()::contains))
            throw new VerificationException(Failure.WRONG_AUDIENCE);

        Object email = claims.get("email");
        if (!(email instanceof String e) || e.isBlank()) throw new VerificationException(Failure.NO_EMAIL);
        return new AccessIdentity(e.trim().toLowerCase(Locale.ROOT), claims.getSubject());
    }

    private PublicKey keyFor(String kid) {
        if (kid == null) throw new VerificationException(Failure.UNKNOWN_KEY);
        Instant now = clock.instant();
        boolean stale = now.isAfter(fetchedAt.plusSeconds(properties.jwksCacheSeconds()));
        PublicKey key = keys.get(kid);
        if (key == null || stale) {
            refresh(now, key == null);
            key = keys.get(kid);
        }
        if (key == null) throw new VerificationException(Failure.UNKNOWN_KEY);
        return key;
    }

    private synchronized void refresh(Instant now, boolean unknownKid) {
        // 다른 스레드가 방금 받아 왔으면 다시 받지 않는다. 모르는 kid 재조회도 최소 간격을 지킨다.
        if (now.isBefore(fetchedAt.plus(MIN_REFRESH_INTERVAL))) return;
        if (!unknownKid && now.isBefore(fetchedAt.plusSeconds(properties.jwksCacheSeconds()))) return;
        try {
            JwkSet set = Jwks.setParser().build().parse(keySource.fetchJwks());
            Map<String, PublicKey> next = new HashMap<>();
            for (Jwk<?> jwk : set.getKeys()) {
                if (jwk.getId() != null && jwk.toKey() instanceof PublicKey pk) next.put(jwk.getId(), pk);
            }
            keys = Map.copyOf(next);
            log.info("Access 공개키 갱신 kids={}", next.keySet());
        } catch (RuntimeException e) {
            // 받아 오지 못하면 기존 키를 계속 쓴다. 기존 키로 안 풀리는 토큰은 거부된다(닫힌 쪽 실패).
            log.warn("Access 공개키를 받아 오지 못했다 — 기존 키를 유지한다. err={}", e.toString());
        } finally {
            fetchedAt = now;
        }
    }
}
