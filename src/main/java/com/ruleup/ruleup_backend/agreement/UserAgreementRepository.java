package com.ruleup.ruleup_backend.agreement;
import com.ruleup.ruleup_backend.agreement.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, UUID> {
    /** append-only 이력 전체(최신순) — 현재 상태는 타입별 첫 행으로 계산한다. */
    List<UserAgreement> findByUser_IdOrderByCreatedAtDescIdDesc(UUID userId);
}
