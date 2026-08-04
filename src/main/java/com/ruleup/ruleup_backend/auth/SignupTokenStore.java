package com.ruleup.ruleup_backend.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ruleup.ruleup_backend.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * signupToken 1회용(jti 소모) 기록 — 계약: 사용된 signupToken 재제출은 400 INVALID_SIGNUP_TOKEN.
 * 토큰 자체는 stateless JWT(5분)라 서버 저장이 없으므로, "사용됨" 표시만 TTL 캐시에 남긴다.
 * 단일 인스턴스 전제(MVP) — 스케일 아웃 시 Redis 로 이전한다.
 */
@Component
public class SignupTokenStore {

    private final Cache<String, Boolean> usedJti;

    public SignupTokenStore(AppProperties props) {
        // 토큰 TTL(5분)보다 약간 길게 보관 — 만료된 토큰은 어차피 서명 검증에서 걸러진다
        this.usedJti = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.jwt().signupTokenTtl() + 60))
                .maximumSize(100_000)
                .build();
    }

    /** jti 소모 시도. @return true = 최초 사용(성공), false = 이미 사용된 토큰. */
    public boolean consume(String jti) {
        return usedJti.asMap().putIfAbsent(jti, Boolean.TRUE) == null;
    }

    /** 소모 없이 사용 여부만 확인 — 가입 재제출(재사용) 선검사용. */
    public boolean isUsed(String jti) {
        return usedJti.getIfPresent(jti) != null;
    }
}
