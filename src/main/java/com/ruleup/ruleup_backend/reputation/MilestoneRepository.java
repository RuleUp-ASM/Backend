package com.ruleup.ruleup_backend.reputation;

import com.ruleup.ruleup_backend.reputation.domain.Milestone;
import com.ruleup.ruleup_backend.reputation.domain.MilestoneType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 마일스톤 접근(append-only). */
public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

    boolean existsByUserIdAndTypeAndDedupKey(UUID userId, MilestoneType type, String dedupKey);

    boolean existsByUserIdAndType(UUID userId, MilestoneType type);

    /** 히스토리 피드: 달성 시각 역순(상한은 Pageable). */
    List<Milestone> findByUserIdOrderByAchievedAtDescCreatedAtDesc(UUID userId, Pageable pageable);
}
