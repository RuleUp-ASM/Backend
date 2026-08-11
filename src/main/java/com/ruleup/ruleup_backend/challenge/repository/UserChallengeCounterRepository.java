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
 *
 * <p><b>락 순서는 예외 없이 사용자 → 챌린지다.</b> 반대로 잡는 코드(챌린지 행을 쥔 채 카운터를 갱신)를
 * 새로 만들면 가입 경로와 데드락 사이클이 생긴다. 재계산({@link #countActiveSlots}·{@link #setCount})은
 * 챌린지 행 락을 전혀 잡지 않으므로 사이클을 만들 수 없다 — 다만 <b>호출부가 챌린지 락을 쥐고 있지 않을 때만</b>
 * 불러야 그 성질이 유지된다(자세한 규칙은 {@code UserJoinCounterService}).
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

    /**
     * <b>슬롯 사용량의 진실</b> — 원천(멤버십 + 챌린지 상태)에서 직접 센다.
     *
     * <p>시작 전(UPCOMING) 방도 센다: 가입이 열려 있어 실제로 슬롯을 쓴다.
     * 종료(COMPLETED) 방은 빼고, 삭제된 방도 뺀다 — {@code leave()} 가 {@code deletedAt IS NULL} 인 방만
     * 잡으므로, 삭제된 방을 세면 사용자가 영영 비울 수 없는 슬롯이 된다.
     *
     * <p>락을 잡지 않는 읽기라 카운터 행 락을 이미 쥔 상태에서 불러도 안전하다.
     */
    @Query(value = "SELECT COUNT(*) FROM challenge_members m "
            + "JOIN challenges ch ON ch.id = m.challenge_id "
            + "WHERE m.user_id = :userId AND m.status = 'ACTIVE' "
            + "  AND ch.deleted_at IS NULL AND ch.status <> 'COMPLETED'", nativeQuery = true)
    int countActiveSlots(@Param("userId") UUID userId);

    /**
     * 값이 실제로 다를 때만 덮어쓴다. 반환값(변경된 행 수)이 곧 "고쳤다"의 판정이라
     * 보정 통계가 부풀지 않는다(스캔과 재계산 사이에 사용자가 스스로 맞춘 경우를 세지 않는다).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE user_challenge_counters SET active_join_count = :value "
            + "WHERE user_id = :userId AND active_join_count <> :value", nativeQuery = true)
    int setCount(@Param("userId") UUID userId, @Param("value") int value);
}
