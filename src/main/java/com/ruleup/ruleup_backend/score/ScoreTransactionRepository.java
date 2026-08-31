package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.ScoreTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 점수 변동 원장 읽기. 인덱스 (user_id, created_at DESC, id DESC) 를 그대로 탄다. */
public interface ScoreTransactionRepository extends JpaRepository<ScoreTransaction, UUID> {

    /**
     * 최근 변동 — 실제로 점수가 움직인 행만. 한도나 0~2,000 경계에 걸려 반영량이 0이었던 행은
     * 화면에 "0점 변동"으로 보이면 혼란만 주므로 뺀다(원장에는 그대로 남아 감사에 쓰인다).
     */
    @Query("""
            SELECT t FROM ScoreTransaction t
            WHERE t.userId = :userId AND t.appliedDelta <> 0
            ORDER BY t.createdAt DESC, t.id DESC""")
    List<ScoreTransaction> findRecent(@Param("userId") UUID userId, Pageable pageable);

    /** 같은 이벤트가 두 번 쌓이는 것을 막는 최종 방어선의 조회 짝. */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** 보관 기간(1년) 안의 변동을 오래된 순으로 — 월말 스냅샷을 접어 만들기 위한 순서다. */
    @Query("""
            SELECT t FROM ScoreTransaction t
            WHERE t.userId = :userId AND t.createdAt >= :since
            ORDER BY t.createdAt ASC, t.id ASC""")
    List<ScoreTransaction> findSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
