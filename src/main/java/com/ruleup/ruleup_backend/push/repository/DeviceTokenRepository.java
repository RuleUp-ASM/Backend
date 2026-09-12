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
     * 묶음 토큰 조회 — {@code (userId, token)} 행을 <b>한 번에</b> 읽는다.
     * {@code ixDeviceTokenActive (userId, isActive)} 를 탄다.
     *
     * <p>구 구조는 「활성 기기가 있는 유저」를 묶음으로 묻고 <b>실제 토큰은 알림마다 다시</b>
     * 물었다. 한 묶음이 최대 1,000건(SQS 100건 × 수신 10건)이므로 토큰 조회만 1,000번 나갔다 —
     * 묶음 조회를 해 둔 의미가 사라지는 자리였다.
     *
     * <p>이 쿼리 하나가 두 가지를 다 준다: 발송에 쓸 토큰과, 판정 ⑧단계의 「활성 기기 있음」
     * 여부(= 이 유저의 행이 하나라도 있는가). 그래서 유저 목록 조회를 따로 두지 않는다.
     */
    @Query("select t.userId, t.token from DeviceToken t where t.userId in :userIds and t.active = true")
    List<Object[]> findActiveTokensByUserIds(@Param("userIds") List<UUID> userIds);

    /**
     * 죽은 토큰 비활성화 — <b>지우지 않는다</b>. 「알림이 안 와요」를 받았을 때 그 기기가 언제 왜
     * 빠졌는지 볼 자리가 없으면 CS 가 서버 로그로 구분할 수 없다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update DeviceToken t set t.active = false, t.lastSeenAt = :at where t.token in :tokens")
    int deactivate(@Param("tokens") List<String> tokens, @Param("at") java.time.Instant at);
}
