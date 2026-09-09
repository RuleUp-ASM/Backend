package com.ruleup.ruleup_backend.admin.service;

import com.ruleup.ruleup_backend.admin.domain.AdminAction;
import com.ruleup.ruleup_backend.admin.dto.AdminDtos;
import com.ruleup.ruleup_backend.admin.repository.AnomalySignalRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.inquiry.InquiryRepository;
import com.ruleup.ruleup_backend.inquiry.domain.InquiryStatus;
import com.ruleup.ruleup_backend.sanction.SanctionRepository;
import com.ruleup.ruleup_backend.sanction.domain.SanctionTrack;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 모니터링 대시보드 — 백오피스 공통 5-2-1 B {@code GET /dashboard/summary}.
 *
 * <h4>지표와 가드레일은 층이 다르다</h4>
 * 지표(적체·SLA·접수 비중)는 <b>추세를 보는 값</b>이라 목표를 벗어나면 운영을 조정한다. 반면
 * {@code guardrails} 는 <b>0이어야 하는 값</b>이다 — 백오피스 § 3 이 「고지 없이 집행된 직권 제재
 * 0건」처럼 못박아 둔 것들이며, 1이 되는 순간 조정이 아니라 사고다. 그래서 한 응답에 담되
 * 필드를 나눠 두고, 콘솔이 이 묶음만 경고로 띄운다.
 *
 * <h4>왜 지표를 별도 집계 테이블로 두지 않나</h4>
 * 운영자가 2명이고 조회는 하루 몇 번이다. 집계 테이블을 만들면 그 테이블을 채우는 배치가
 * 생기고, <b>배치가 밀리면 가드레일이 0으로 보인다</b> — 늦게 아는 것보다 조금 느린 쿼리가 낫다.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final JdbcTemplate jdbc;
    private final InquiryRepository inquiryRepository;
    private final SanctionRepository sanctionRepository;
    private final AnomalySignalRepository anomalyRepository;
    private final AdminAuditService auditService;

    @Transactional(readOnly = true)
    public AdminDtos.DashboardSummary summary(UUID operatorId, String range) {
        // 가드레일을 언제 봤는지가 남아야 "언제부터 알고 있었나"에 답할 수 있다.
        auditService.allowed(operatorId, AdminAction.DASHBOARD_VIEW, null, null, range);

        Duration window = switch (range == null ? "7d" : range) {
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            case "90d" -> Duration.ofDays(90);
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };
        Instant to = Instant.now();
        Instant from = to.minus(window);

        return new AdminDtos.DashboardSummary(
                range == null ? "7d" : range, from.toString(), to.toString(),
                guardrails(from, to), reports(from, to), inquiries(from, to),
                sanctions(from, to), anomalies(from, to), members(from, to), challenges(from, to));
    }

    // ===== 가드레일 — 전부 0이어야 한다 =====

    private AdminDtos.Guardrails guardrails(Instant from, Instant to) {
        return new AdminDtos.Guardrails(
                // ① 고지 없이 집행된 직권 제재. 자동 트랙은 고지 주체가 달라 제외한다.
                sanctionRepository.countByNotifiedAtIsNullAndTrack(SanctionTrack.DISCRETIONARY),

                // ② 감사 로그 없이 집행된 제재 — 집행마다 SANCTION_APPLY 가 선행해야 한다.
                //    시각으로 잇지 않고 대상으로 잇는다: 같은 유저에 대한 기록이 하나도 없으면 위반이다.
                count("""
                        SELECT COUNT(*) FROM sanctions s
                         WHERE s.track = 'DISCRETIONARY'
                           AND NOT EXISTS (SELECT 1 FROM admin_audit_logs l
                                            WHERE l.action = 'SANCTION_APPLY'
                                              AND l.target_type = 'USER'
                                              AND l.target_id = s.user_id
                                              AND l.result = 'ALLOWED')
                        """),

                // ③ 검토 근거 없는 잠금·영구 정지. 자동 승격 경로가 생겼다는 신호이므로
                //    "근거 없이 직권으로 걸 수 있다"는 사실 자체를 센다(source = DIRECT 는 정상이다).
                count("""
                        SELECT COUNT(*) FROM sanctions
                         WHERE track = 'AUTO' AND type IN ('LOCK', 'BAN')
                        """),

                // ④ 거부된 백오피스 접근 — 급증이 우회 시도의 신호다.
                count("SELECT COUNT(*) FROM admin_audit_logs WHERE result = 'DENIED'"
                        + " AND occurred_at BETWEEN ? AND ?", ts(from), ts(to)));
    }

    // ===== 지표 =====

    private AdminDtos.Reports reports(Instant from, Instant to) {
        return new AdminDtos.Reports(
                count("SELECT COUNT(*) FROM reports WHERE status = 'PENDING'"),
                jdbc.queryForList("SELECT MIN(created_at) AS at FROM reports WHERE status = 'PENDING'")
                        .stream().map(r -> r.get("at")).filter(java.util.Objects::nonNull)
                        .map(String::valueOf).findFirst().orElse(null),
                count("SELECT COUNT(*) FROM reports WHERE resolved_at BETWEEN ? AND ?",
                        ts(from), ts(to)),
                count("SELECT COUNT(*) FROM reports WHERE created_at BETWEEN ? AND ?",
                        ts(from), ts(to)));
    }

    private AdminDtos.Inquiries inquiries(Instant from, Instant to) {
        List<Map<String, Object>> byCategory = jdbc.queryForList(
                "SELECT origin_category AS c, COUNT(*) AS n FROM inquiries"
                        + " WHERE created_at BETWEEN ? AND ? GROUP BY origin_category",
                ts(from), ts(to));

        return new AdminDtos.Inquiries(
                inquiryRepository.countByStatus(InquiryStatus.RECEIVED),
                inquiryRepository.countByCreatedAtBetween(from, to),
                inquiryRepository.countByAnsweredAtBetween(from, to),
                medianResponseHours(from, to),
                byCategory.stream()
                        .map(r -> new AdminDtos.CategoryCount(String.valueOf(r.get("c")),
                                ((Number) r.get("n")).longValue()))
                        .toList());
    }

    /**
     * 접수→답변 소요 시간의 중앙값.
     *
     * <p>평균이 아니라 중앙값인 이유는 § 5.8 이 그렇게 정했기 때문이고, 그 이유는 CS 분포가
     * 한쪽으로 길기 때문이다 — 오래 끈 한 건이 평균을 통째로 끌어올린다.
     *
     * <p>윈도 함수 대신 정렬 후 가운데를 집는다. 표본이 하루 몇 건 수준이라 전부 읽어도 되고,
     * MySQL 버전에 따라 달라지는 문법을 피할 수 있다.
     */
    private Double medianResponseHours(Instant from, Instant to) {
        List<Long> minutes = jdbc.query(
                "SELECT TIMESTAMPDIFF(MINUTE, created_at, answered_at) FROM inquiries"
                        + " WHERE answered_at BETWEEN ? AND ? ORDER BY 1",
                (rs, i) -> rs.getLong(1), ts(from), ts(to));
        if (minutes.isEmpty()) return null;

        int mid = minutes.size() / 2;
        double median = (minutes.size() % 2 == 1) ? minutes.get(mid)
                : (minutes.get(mid - 1) + minutes.get(mid)) / 2.0;
        return Math.round(median / 60.0 * 10) / 10.0;
    }

    private AdminDtos.Sanctions sanctions(Instant from, Instant to) {
        Instant now = Instant.now();
        return new AdminDtos.Sanctions(
                // 활성 판정은 세 경우다 — 동결·영구를 빼면 실제보다 적게 보인다.
                count("""
                        SELECT COUNT(*) FROM sanctions
                         WHERE revoked_at IS NULL
                           AND (frozen_remaining_sec IS NOT NULL OR ends_at IS NULL OR ends_at > ?)
                        """, ts(now)),
                count("SELECT COUNT(*) FROM sanctions WHERE track = 'DISCRETIONARY'"
                        + " AND starts_at BETWEEN ? AND ?", ts(from), ts(to)),
                count("SELECT COUNT(*) FROM sanctions WHERE track = 'AUTO'"
                        + " AND starts_at BETWEEN ? AND ?", ts(from), ts(to)),
                count("SELECT COUNT(*) FROM sanctions WHERE revoked_at BETWEEN ? AND ?",
                        ts(from), ts(to)));
    }

    private AdminDtos.Anomalies anomalies(Instant from, Instant to) {
        return new AdminDtos.Anomalies(
                anomalyRepository.countByReviewedAtIsNull(),
                anomalyRepository.countByDetectedAtBetween(from, to));
    }

    private AdminDtos.Members members(Instant from, Instant to) {
        return new AdminDtos.Members(
                count("SELECT COUNT(*) FROM users WHERE status <> 'WITHDRAWN' AND deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM users WHERE status = 'SUSPENDED'"),
                count("SELECT COUNT(*) FROM users WHERE created_at BETWEEN ? AND ?", ts(from), ts(to)));
    }

    private AdminDtos.Challenges challenges(Instant from, Instant to) {
        return new AdminDtos.Challenges(
                count("SELECT COUNT(*) FROM challenges WHERE status = 'ACTIVE' AND deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM challenges WHERE created_at BETWEEN ? AND ?",
                        ts(from), ts(to)));
    }

    // ===== 내부 =====

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }
}
