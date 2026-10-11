package com.ruleup.ruleup_backend.admin.access;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** 시험용 Cloudflare Access 발급자 — 실제 Access 토큰과 같은 모양(RS256·kid·iss·aud·email)을 만든다. */
public final class AccessTokens {

    public static final String TEAM = "https://ruleup-test.cloudflareaccess.com";
    public static final String AUD = "aud-admin-api-test";

    private final KeyPair keyPair = Jwts.SIG.RS256.keyPair().build();
    private final String kid;

    public AccessTokens(String kid) {
        this.kid = kid;
    }

    public String jwks() {
        PublicJwk<PublicKey> jwk = Jwks.builder().key(keyPair.getPublic()).id(kid).build();
        return "{\"keys\":[" + Jwks.json(jwk) + "]}";
    }

    public String issue(String email) {
        return issue(Map.of("email", email), TEAM, AUD, Instant.now().plusSeconds(600));
    }

    public String issue(Map<String, Object> claims, String issuer, String audience, Instant expiresAt) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject("access-user-id")
                .claims(claims)
                .issuedAt(Date.from(expiresAt.minusSeconds(600)))
                .expiration(Date.from(expiresAt))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
