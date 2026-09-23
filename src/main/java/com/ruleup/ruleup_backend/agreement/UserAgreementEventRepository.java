package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 동의·철회 이력(append-only). 현재 상태 조회는 {@link UserAgreementStateRepository}를 쓴다. */
public interface UserAgreementEventRepository extends JpaRepository<UserAgreementEvent, UUID> {

    List<UserAgreementEvent> findByUser_IdOrderByCreatedAtDescIdDesc(UUID userId);

    List<UserAgreementEvent> findByUser_IdAndAgreementTypeOrderByCreatedAtDesc(UUID userId, AgreementType type);

    long countByUser_Id(UUID userId);
}
