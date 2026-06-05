package com.ruleup.ruleup_backend.reputation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** reputation_scores 접근. 기본 CRUD만 필요해서 메서드 추가 없음. */
public interface ReputationScoreRepository extends JpaRepository<ReputationScore, UUID> {
}