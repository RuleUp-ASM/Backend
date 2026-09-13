package com.ruleup.ruleup_backend.verification.repository;

import com.ruleup.ruleup_backend.verification.domain.VerificationFailureDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 실패 상세 — PK 로만 오간다. 사유별 집계는 대시보드 쿼리가 인덱스로 직접 본다. */
public interface VerificationFailureDetailRepository
        extends JpaRepository<VerificationFailureDetail, UUID> {
}
