package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.reputation.domain.ReputationSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 일별 온도 스냅샷 접근. */
public interface ReputationSnapshotRepository extends JpaRepository<ReputationSnapshot, UUID> {

    boolean existsByUserIdAndSnapshotDate(UUID userId, LocalDate snapshotDate);

    /** 최근 변동(온도 상세): 날짜 내림차순 상위 N. */
    List<ReputationSnapshot> findByUserIdOrderBySnapshotDateDesc(UUID userId, Pageable pageable);

    /** 통계 mannerDelta: 기간 내 스냅샷(오름차순). */
    List<ReputationSnapshot> findByUserIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            UUID userId, LocalDate from, LocalDate to);

    /** 기간 시작 이전의 마지막 스냅샷(기간 시작 온도 기준선). */
    Optional<ReputationSnapshot> findFirstByUserIdAndSnapshotDateLessThanOrderBySnapshotDateDesc(
            UUID userId, LocalDate before);
}
