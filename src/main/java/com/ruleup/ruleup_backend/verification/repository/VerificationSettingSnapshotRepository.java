package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.SettingKind;
import com.ruleup.ruleup_backend.verification.domain.VerificationSettingSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 멤버 인증 설정 스냅샷 접근. */
public interface VerificationSettingSnapshotRepository extends JpaRepository<VerificationSettingSnapshot, UUID> {

    /**
     * 날짜 D 에 적용되던 설정 — {@code effectiveFrom <= D} 중 가장 늦은 것.
     * 같은 날 여러 번 저장됐으면 나중 것이 이긴다(createdAt 내림차순).
     */
    @Query("SELECT s FROM VerificationSettingSnapshot s " +
            "WHERE s.challengeMemberId = :memberId AND s.kind = :kind AND s.effectiveFrom <= :date " +
            "ORDER BY s.effectiveFrom DESC, s.createdAt DESC")
    List<VerificationSettingSnapshot> findEffective(@Param("memberId") UUID memberId,
                                                    @Param("kind") SettingKind kind,
                                                    @Param("date") LocalDate date,
                                                    Pageable pageable);
}
