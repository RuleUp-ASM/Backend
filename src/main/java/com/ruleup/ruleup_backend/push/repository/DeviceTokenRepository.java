package com.ruleup.ruleup_backend.push.repository;

import com.ruleup.ruleup_backend.push.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(UUID userId);

    void deleteByToken(String token);

    void deleteByUserIdAndToken(UUID userId, String token);

    /**
     * 활성 기기가 있는 유저 — 컨슈머의 묶음 조회다. 건당 물으면 08:00 8만 건에서 N+1 이 난다.
     * {@code ixDeviceTokenActive (userId, isActive)} 로 커버링이라 테이블을 읽지 않는다.
     */
    @Query("select distinct t.userId from DeviceToken t where t.userId in :userIds and t.active = true")
    List<UUID> findUserIdsWithActiveToken(@Param("userIds") List<UUID> userIds);

    @Query("select t.token from DeviceToken t where t.userId = :userId and t.active = true")
    List<String> findActiveTokens(@Param("userId") UUID userId);

    /**
     * 죽은 토큰 비활성화 — <b>지우지 않는다</b>. 「알림이 안 와요」를 받았을 때 그 기기가 언제 왜
     * 빠졌는지 볼 자리가 없으면 CS 가 서버 로그로 구분할 수 없다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update DeviceToken t set t.active = false, t.lastSeenAt = :at where t.token in :tokens")
    int deactivate(@Param("tokens") List<String> tokens, @Param("at") java.time.Instant at);
}
