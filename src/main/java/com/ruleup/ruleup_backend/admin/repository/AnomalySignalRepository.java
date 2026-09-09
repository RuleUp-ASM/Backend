package com.ruleup.ruleup_backend.admin.repository;

import com.ruleup.ruleup_backend.admin.domain.AnomalySignal;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AnomalySignalRepository extends JpaRepository<AnomalySignal, UUID> {

    /** 미검토 신호를 강도순으로 꺼내는 대시보드 주 쿼리. */
    @Query("""
            select s from AnomalySignal s
             where s.reviewedAt is null
             order by s.score desc, s.detectedAt asc
            """)
    List<AnomalySignal> findUnreviewed(Limit limit);

    /**
     * 콘솔 큐 — (점수 내림차순, 탐지 시각 오름차순) 두 축이며 커서가 그 축을 그대로 이어받는다.
     *
     * <p>커서 조건이 중첩된 이유는 정렬 축이 둘이기 때문이다. 점수만 비교하면 <b>같은 점수의
     * 신호가 통째로 건너뛰어진다</b> — 임계값을 넉넉히 잡아 둔 초기 운영에서는 같은 점수가
     * 무더기로 생기므로 그 누락이 곧 검토 누락이 된다.
     */
    @Query("""
            select s from AnomalySignal s
             where (:signalType is null or s.signalType = :signalType)
               and (:onlyUnreviewed = false or s.reviewedAt is null)
               and (:minScore is null or s.score >= :minScore)
               and (:cursorScore is null
                    or s.score < :cursorScore
                    or (s.score = :cursorScore and s.detectedAt > :cursorDetectedAt))
             order by s.score desc, s.detectedAt asc
            """)
    List<AnomalySignal> findQueue(@Param("signalType") AnomalySignal.SignalType signalType,
                                  @Param("onlyUnreviewed") boolean onlyUnreviewed,
                                  @Param("minScore") Integer minScore,
                                  @Param("cursorScore") Integer cursorScore,
                                  @Param("cursorDetectedAt") Instant cursorDetectedAt,
                                  Limit limit);

    /** 유저 통합 뷰의 이상탐지 이력 섹션. */
    List<AnomalySignal> findByTargetUserIdOrderByDetectedAtDesc(UUID targetUserId);

    long countByReviewedAtIsNull();

    long countByDetectedAtBetween(Instant from, Instant to);
}
