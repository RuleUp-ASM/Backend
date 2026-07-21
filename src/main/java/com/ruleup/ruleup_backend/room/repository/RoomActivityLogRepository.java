package com.ruleup.ruleup_backend.room.repository;

import com.ruleup.ruleup_backend.room.domain.RoomActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 방 내부 기록 활동 로그 접근(append-only). */
public interface RoomActivityLogRepository extends JpaRepository<RoomActivityLog, UUID> {

    /** 챌린지의 활동 로그(최신순). 방 하드삭제 후 감사 조회에도 사용. */
    List<RoomActivityLog> findByChallengeIdOrderByCreatedAtDesc(UUID challengeId);

    long countByChallengeId(UUID challengeId);
}
