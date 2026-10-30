package com.ruleup.ruleup_backend.auth.repository;

import com.ruleup.ruleup_backend.auth.domain.RefreshToken;
import com.ruleup.ruleup_backend.auth.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** refresh_tokens 접근. 재발급/검증 시 해시(BINARY 32)로 토큰을 찾는다. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /**
     * 회전 대상 RT를 배타 잠금으로 읽는다.
     * 같은 RT가 동시에 제출돼도 한 요청만 활성 상태를 볼 수 있어야 한다.
     * 후발 요청은 선발 트랜잭션 커밋 뒤 revoked 상태를 읽고 회전 유예·재사용 감지 경로로 간다.
     * {@link #lockActiveFamily} 를 먼저 잡은 뒤에 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") byte[] tokenHash);

    /** 잠금 없이 family(=로그인 세션) ID만 읽는다 — family 잠금을 잡기 전 단계. family_id 는 바뀌지 않는다. */
    @Query("select t.familyId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") byte[] tokenHash);

    /**
     * family 의 살아 있는 RT 전체를 배타 잠금 — 같은 세션의 갱신·로그아웃을 직렬화한다
     * (idx_refresh_tokens_family_active 범위 잠금이라 잠금 중엔 이 family 에 새 RT도 끼어들지 못한다).
     * 회전 유예로 같은 세션에 RT가 여럿 살아 있을 수 있어 제출된 토큰 한 행만 잠가서는 부족하다.
     * 갱신·로그아웃 모두 <b>개별 토큰보다 이 잠금을 먼저</b> 잡아야 서로 교착하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.familyId = :familyId and t.revokedAt is null")
    List<RefreshToken> lockActiveFamily(@Param("familyId") UUID familyId);

    /**
     * 이 토큰을 회전해 발급된 후속 토큰들 — 회전 유예 판단용(parent_token_id 인덱스).
     * 잠금 읽기로 최신 상태를 본다. 일반 읽기는 family 잠금 전 스냅샷이라 그 사이 로그아웃이 폐기한 후속을 살아 있다고 볼 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.parentTokenId = :parentTokenId")
    List<RefreshToken> findByParentTokenIdForUpdate(@Param("parentTokenId") UUID parentTokenId);

    /** 사용자의 활성 RT 전체 revoke — 로그아웃 전체·신규 기기 로그인·탈퇴·정지 시. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    /** 해당 family의 활성 RT 전체 revoke — 재사용 감지(탈취 대응)·로그아웃 시. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** 재사용 탐지 이력이 없는 만료 RT를 expires_at 순으로 작은 배치 삭제. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM refresh_tokens
            WHERE reuse_detected_at IS NULL
              AND revoked_at IS NULL
              AND expires_at < :cutoff
            ORDER BY expires_at, id
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /** 재사용 탐지 이력이 없는 폐기 RT를 revoked_at 순으로 작은 배치 삭제. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM refresh_tokens
            WHERE reuse_detected_at IS NULL
              AND revoked_at < :cutoff
            ORDER BY revoked_at, id
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteRevokedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /** 보안 감사 보관기간이 지난 재사용 탐지 RT를 작은 배치 삭제. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM refresh_tokens
            WHERE reuse_detected_at < :cutoff
            ORDER BY reuse_detected_at, id
            LIMIT :batchSize
            """, nativeQuery = true)
    int deleteReuseDetectedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
