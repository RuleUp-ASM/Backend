package com.ruleup.ruleup_backend.admin.repository;

import com.ruleup.ruleup_backend.admin.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** <b>append only</b> — 수정·삭제 메서드를 노출하지 않는다. */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    /** 운영자별 활동 추적 · 무목적 열람 비율 점검. */
    List<AdminAuditLog> findByOperatorIdOrderByOccurredAtDesc(UUID operatorId);

    /** 특정 유저·챌린지에 가해진 조작 전수 조회 — 재검토 대응의 기본 쿼리. */
    List<AdminAuditLog> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            AdminAuditLog.TargetType targetType, UUID targetId);

    /** 일반 계정 접근 시도 탐지 — DENIED 급증이 우회 시도의 신호다. */
    long countByResult(AdminAuditLog.Result result);
}
