package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 동의 현재 상태 — 유저당 최대 7행이라 유저 단위 전체 조회가 PK 선두 컬럼만으로 끝난다. */
public interface UserAgreementStateRepository
        extends JpaRepository<UserAgreementState, UserAgreementState.Key> {

    List<UserAgreementState> findByUserId(UUID userId);

    long countByUserId(UUID userId);
}
