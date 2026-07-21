package com.ruleup.ruleup_backend.room.repository;

import com.ruleup.ruleup_backend.room.domain.Notice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 공지 접근(물리 삭제 — 소프트삭제 없음). */
public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    Optional<Notice> findByIdAndChallengeId(UUID id, UUID challengeId);

    /** 목록: 고정 우선 → 최신순(상한은 Pageable). */
    List<Notice> findByChallengeIdOrderByPinnedDescCreatedAtDesc(UUID challengeId, Pageable pageable);

    /** 단일 pin: 현재 고정 공지(있으면). */
    Optional<Notice> findByChallengeIdAndPinnedTrue(UUID challengeId);

    /** 미읽음 계산: 공지 총수. */
    long countByChallengeId(UUID challengeId);
}
