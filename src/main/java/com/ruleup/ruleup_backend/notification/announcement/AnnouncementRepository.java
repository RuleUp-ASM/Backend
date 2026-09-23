package com.ruleup.ruleup_backend.notification.announcement;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    /**
     * 팬아웃 대기분 — 취소분과 예약 시각이 안 된 건은 제외한다.
     *
     * <p>세 조건을 한 쿼리에 두는 이유는 잡이 「대기 행이 없으면 인덱스 조회 한 번」이라는
     * 성질을 유지하기 위해서다. 꺼내 놓고 자바에서 거르면 취소된 공지가 쌓일수록 잡이 느려진다.
     */
    @Query("""
            select a from Announcement a
             where a.fannedOutAt is null
               and a.canceledAt is null
               and (a.scheduledAt is null or a.scheduledAt <= :now)
             order by a.id asc
            """)
    List<Announcement> findPending(@Param("now") Instant now, Limit limit);

    /** 발행 이력 — 최신순. */
    List<Announcement> findAllByOrderByCreatedAtDesc(Limit limit);
}
