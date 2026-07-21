package com.ruleup.ruleup_backend.room.repository;

import com.ruleup.ruleup_backend.room.domain.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 공지 읽음 접근. */
public interface NoticeReadRepository extends JpaRepository<NoticeRead, UUID> {

    boolean existsByNoticeIdAndUserId(UUID noticeId, UUID userId);

    /** 목록 isRead 플래그: 내가 읽은 공지들(주어진 공지 집합 중). */
    List<NoticeRead> findByUserIdAndNoticeIdIn(UUID userId, Collection<UUID> noticeIds);

    /** 미읽음 계산: 내가 이 챌린지에서 읽은 공지 수. */
    long countByChallengeIdAndUserId(UUID challengeId, UUID userId);

    /** 삭제 시 읽음 정리. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM NoticeRead r WHERE r.noticeId = :noticeId")
    void deleteByNoticeId(@Param("noticeId") UUID noticeId);
}
