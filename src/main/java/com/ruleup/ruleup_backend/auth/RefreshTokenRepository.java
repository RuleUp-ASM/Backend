package com.ruleup.ruleup_backend.auth;
import com.ruleup.ruleup_backend.auth.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/** refresh_tokens 접근. 재발급/검증 시 해시로 토큰을 찾는다. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}