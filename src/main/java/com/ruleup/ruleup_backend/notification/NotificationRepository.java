package com.ruleup.ruleup_backend.notification;

import com.ruleup.ruleup_backend.notification.domain.Notification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * 알림 센터 목록 — {@code idx_notifications_inbox (user_id, tab, id)} 를 그대로 탄다.
     *
     * <p>정렬 키가 {@code id} 하나인 것이 핵심이다. 커서가 base64(id) 라 {@code created_at} 으로
     * 정렬하면 커서 조건을 인덱스에 밀어넣을 수 없고, 00시 판정 배치가 같은 밀리초에 수만 행을
     * 넣으면 <b>페이지 경계에서 항목이 중복되거나 사라진다</b>. UUIDv7 이라 동점 자체가 없다.
     */
    @Query("""
            select n from Notification n
             where n.userId = :userId
               and n.tab = :tab
               and (:cursorId is null or n.id < :cursorId)
             order by n.id desc
            """)
    List<Notification> findInbox(@Param("userId") UUID userId,
                                 @Param("tab") byte tab,
                                 @Param("cursorId") UUID cursorId,
                                 Limit limit);

    /** 탭 구분 없이 한 유저의 전부 — 파기·검증 경로용. 화면 조회는 {@link #findInbox} 를 쓴다. */
    List<Notification> findByUserIdOrderByIdDesc(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    /** 발행 멱등 — UNIQUE 가 최종 보증이고 이건 재시도가 예외로 가지 않게 하는 선조회다. */
    boolean existsByDedupKey(String dedupKey);

    /** 6개월 파기 배치 — {@code idx_notifications_purge (created_at)}. */
    List<Notification> findByCreatedAtBefore(Instant threshold, Limit limit);

    /**
     * 인터벌 억제 판정 — 컨슈머가 <b>청크 단위로 한 번에</b> 묻는다. 건당 조회 금지.
     *
     * <p>{@code idx_notifications_suppress (user_id, suppress_key, pushed_at)} 의 세 컬럼이
     * 전부 들어 있어 <b>커버링 인덱스</b>다 — 테이블을 읽지 않는다.
     *
     * <p>{@code pushed_at} 이 NULL 인 행(미발송·억제됨)은 범위 조건에서 자동으로 빠진다.
     * 억제 체인이 생기지 않는 이유다.
     *
     * <p>⚠️ 판정이 조회와 갱신 2단계로 나뉘므로 <b>원자적이지 않다</b>. 같은 유저의 같은 키가
     * 동시에 두 컨슈머에 잡히면 둘 다 통과할 수 있다. 억제는 정책적 완화 장치라 이 정도
     * 누수는 수용한다(구 {@code notification_dedup} 의 조건부 UPSERT 는 원자적이었다).
     */
    @Query("""
            select n.userId, n.suppressKey, max(n.pushedAt)
              from Notification n
             where n.userId in :userIds
               and n.suppressKey in :keys
               and n.pushedAt >= :since
             group by n.userId, n.suppressKey
            """)
    List<Object[]> findLastPushedBySuppressKey(@Param("userIds") List<UUID> userIds,
                                               @Param("keys") List<String> keys,
                                               @Param("since") Instant since);

    /** 발송 성공 도장 — 억제 대상 타입만 넘어온다. */
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.pushedAt = :at where n.id in :ids")
    int markPushed(@Param("ids") List<UUID> ids, @Param("at") Instant at);
}
