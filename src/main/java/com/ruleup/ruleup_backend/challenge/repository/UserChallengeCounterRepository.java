package com.ruleup.ruleup_backend.challenge.repository;

import com.ruleup.ruleup_backend.challenge.domain.UserChallengeCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * 동시 참여 3개 게이트의 사용자 행 락 (백엔드 테크스펙 4-3).
 *
 * <p>사용 순서는 항상 {@link #ensureRow} → {@link #lockCount} → (챌린지 행 락) 이다.
 * 회원 생성 시 0으로 함께 만들지만, 백필 이전 계정·경합을 대비해 가입 트랜잭션에서도 행 존재를 보장한다.
 */
public interface UserChallengeCounterRepository extends JpaRepository<UserChallengeCounter, UUID> {

    /** 행 존재 보장(멱등). 이미 있으면 아무것도 하지 않는다. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_challenge_counters (user_id, active_join_count) VALUES (:userId, 0) "
            + "ON DUPLICATE KEY UPDATE active_join_count = active_join_count", nativeQuery = true)
    void ensureRow(@Param("userId") UUID userId);

    /** 사용자 행을 잠그고 현재 참여 수를 읽는다. 락 순서는 사용자 → 챌린지로 고정. */
    @Query(value = "SELECT active_join_count FROM user_challenge_counters WHERE user_id = :userId FOR UPDATE",
            nativeQuery = true)
    Integer lockCount(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE user_challenge_counters SET active_join_count = active_join_count + 1 "
            + "WHERE user_id = :userId", nativeQuery = true)
    void increment(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE user_challenge_counters SET active_join_count = active_join_count - 1 "
            + "WHERE user_id = :userId AND active_join_count > 0", nativeQuery = true)
    void decrement(@Param("userId") UUID userId);
}
