package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationMethodResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** verification_method_result 접근. daily 1개당 방식 N줄. */
public interface VerificationMethodResultRepository extends JpaRepository<VerificationMethodResult, UUID> {

    List<VerificationMethodResult> findByVerificationDailyId(UUID verificationDailyId);

    Optional<VerificationMethodResult> findByVerificationDailyIdAndMethod(UUID verificationDailyId, String method);
}
