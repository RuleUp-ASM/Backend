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
     * 최근 변동 — 사건 종류가 있는 행만. reason 이 없는 행은 화면에 이름을 붙일 수 없어 건너뛴다
     * (점수 산식 스택 이전에 쌓인 행이 그렇다).
     */
    @Query("""
            SELECT t FROM ScoreTransaction t
            WHERE t.userId = :userId AND t.reason IS NOT NULL
            ORDER BY t.createdAt DESC, t.id DESC""")
    List<ScoreTransaction> findRecent(@Param("userId") UUID userId, Pageable pageable);

    /** 보관 기간(1년) 안의 변동을 오래된 순으로 — 월말 스냅샷을 접어 만들기 위한 순서다. */
    @Query("""
            SELECT t FROM ScoreTransaction t
            WHERE t.userId = :userId AND t.createdAt >= :since
            ORDER BY t.createdAt ASC, t.id ASC""")
    List<ScoreTransaction> findSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
