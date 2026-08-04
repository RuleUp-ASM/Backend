package com.ruleup.ruleup_backend.auth;
import com.ruleup.ruleup_backend.auth.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** refresh_tokens 접근. 재발급/검증 시 해시(BINARY 32)로 토큰을 찾는다. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /** 사용자의 활성 RT 전체 revoke — 로그아웃 전체·신규 기기 로그인·탈퇴·정지 시. */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** 재사용 감지 시 해당 family의 활성 RT 전체 revoke (탈취 대응). */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
