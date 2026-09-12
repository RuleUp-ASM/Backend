package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 동의 현재 상태 — 유저당 최대 7행이라 유저 단위 전체 조회가 PK 선두 컬럼만으로 끝난다. */
public interface UserAgreementStateRepository
        extends JpaRepository<UserAgreementState, UserAgreementState.Key> {

    List<UserAgreementState> findByUserId(UUID userId);

    /**
     * 한 항목의 동의 상태를 <b>여러 유저에 대해 한 번에</b>. 알림 컨슈머가 마케팅 발송 판정에 쓴다 —
     * 건당 {@code hasIndividualConsent} 를 부르면 묶음 조회를 해 둔 의미가 사라진다.
     *
     * <p>PK 선두가 {@code user_id} 라 이 조회도 PK 를 탄다.
     */
    List<UserAgreementState> findByUserIdInAndAgreementType(Collection<UUID> userIds,
                                                            AgreementType agreementType);

    long countByUserId(UUID userId);
}
