package com.ruleup.ruleup_backend.push.repository;

import com.ruleup.ruleup_backend.push.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(UUID userId);

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
    int deactivate(@Param("tokens") List<String> tokens, @Param("at") Instant at);

    /**
     * 이 유저의 <b>다른</b> 활성 토큰을 전부 내린다 — 단일 활성 기기 정책(온보딩 4-3).
     *
     * <p>등록이 upsert 만 하고 끝나면 기기를 바꿔도 이전 기기 토큰이 활성으로 남아, 한 사람의
     * 알림이 <b>두 기기에 동시에</b> 뜬다. 넘겨받은 토큰만 남기고 나머지를 내린다.
     *
     * <p>{@code flushAutomatically} 가 <b>반드시 참이어야 한다</b>. 등록은 이 호출 직전에
     * {@code reassign()} 으로 기존 행을 더티 상태로 만드는데, 플러시 없이 벌크 UPDATE 가 나가면
     * {@code clearAutomatically} 가 그 더티 변경을 <b>버린다</b> — 되살아나야 할 기기가 조용히
     * 비활성으로 남는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceToken t set t.active = false, t.lastSeenAt = :at
             where t.userId = :userId and t.active = true and t.token <> :keepToken
            """)
    int deactivateOthers(@Param("userId") UUID userId, @Param("keepToken") String keepToken,
                         @Param("at") Instant at);

    /** 본인 토큰 해제 — 지우지 않고 내린다. 남의 토큰은 건드리지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DeviceToken t set t.active = false, t.lastSeenAt = :at
             where t.userId = :userId and t.token = :token
            """)
    int deactivateOwn(@Param("userId") UUID userId, @Param("token") String token,
                      @Param("at") Instant at);

    /** 발송 대상 토큰 — 비활성 행은 뺀다. */
    @Query("select t.token from DeviceToken t where t.userId = :userId and t.active = true")
    List<String> findActiveTokensOf(@Param("userId") UUID userId);
}
