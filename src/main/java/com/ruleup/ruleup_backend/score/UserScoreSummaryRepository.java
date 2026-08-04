package com.ruleup.ruleup_backend.score;
import com.ruleup.ruleup_backend.score.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserScoreSummaryRepository extends JpaRepository<UserScoreSummary, UUID> {
}
