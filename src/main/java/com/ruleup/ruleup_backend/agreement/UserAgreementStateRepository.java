package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 이 항목에 <b>동의해 둔</b> 사람 중 저장 버전이 현행과 다른 사람 — 개정 고지 대상.
     *
     * <p>동의한 적 없는 사람({@code agreed=false} · 행 없음)은 제외한다. 그들에게 「약관이
     * 개정됐어요」는 틀린 문장이고, 필수 약관 미동의는 개정과 무관하게 게이트가 이미 막고 있다.
     *
     * <p><b>{@code userId} 커서로 한 쪽씩</b> 준다. 고지를 받아도 재동의하기 전까지는 계속 대상이라,
     * 앞에서부터 상한만큼 자르면 매일 같은 사람만 다시 읽고 뒤쪽은 영영 닿지 않는다. 커서는 직전 쪽의
     * 마지막 {@code userId} 이고 첫 쪽은 null 이다. PK 선두가 {@code user_id} 라 정렬도 PK 를 탄다.
     */
    @Query("""
            select s from UserAgreementState s
             where s.agreementType = :type
               and s.agreed = true
               and s.version <> :currentVersion
               and (:after is null or s.userId > :after)
             order by s.userId asc
            """)
    List<UserAgreementState> findOutdated(@Param("type") AgreementType type,
                                          @Param("currentVersion") String currentVersion,
                                          @Param("after") UUID after,
                                          Limit limit);

    long countByUserId(UUID userId);
}
