package com.ruleup.ruleup_backend.agreement;
import com.ruleup.ruleup_backend.agreement.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, UUID> {
    List<UserAgreement> findByUser_Id(UUID userId);
}