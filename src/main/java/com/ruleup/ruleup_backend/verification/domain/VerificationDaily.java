package com.ruleup.ruleup_backend.verification.domain;
import com.ruleup.ruleup_backend.common.verification.*;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/**
 * 하루 인증 판정 (VerificationDaily 테이블) — 그날 챌린지 단위 최종 판정 [부모].
 * 방식별 평가(VerificationMethodResult)를 종합한 결과. 인증 스펙 §4.
 *  - uq(challengeMemberId, targetDate)로 멤버×날짜 하루 1줄.
 *  - challengeId/userId는 조회 최적화용 비정규화(FK 아님).
 *  - finalizeAfter = 창 닫힘 + maxSignalLagHours. 확정 배치가 status='PENDING' AND finalizeAfter<now 로 폴링.
 * 연관관계 대신 raw UUID만 보유(다른 도메인과 동일 패턴). 판정 로직은 인증 단계에서 확장.
 */
@Entity
@Table(name = "VerificationDaily")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationDaily extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeMemberId", nullable = false, updatable = false)
    private UUID challengeMemberId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;            // 비정규화(조회 최적화)

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;                 // 비정규화

    @Column(name = "targetDate", nullable = false)
    private LocalDate targetDate;        // KST 기준

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VerificationStatus status = VerificationStatus.PENDING;

    @Column(name = "method", length = 40)
    private String method;               // 충족에 기여한 방식(들). AND이면 복수 직렬화.

    @Column(name = "failureReason", length = 40)
    private String failureReason;        // 실패/미충족 사유 코드

    @Column(name = "windowClosesAt")
    private Instant windowClosesAt;      // 창 닫힘 시각(제약형·시간창)

    @Column(name = "finalizeAfter")
    private Instant finalizeAfter;       // 창 닫힘 + maxSignalLagHours = 확정 가능 시각

    @Column(name = "verifiedAt")
    private Instant verifiedAt;          // 확정 시각

    // ===== v2: 확정 경로 + 예비 폴백 이의 윈도우 (테크스펙 v2 §9) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "verifiedVia")
    private VerifiedVia verifiedVia;     // AUTO / MANUAL / MANUAL_FALLBACK (없으면 미확정)

    @Column(name = "disputeClosesAt")
    private Instant disputeClosesAt;     // (레거시) 예비 폴백 이의 윈도우 — 방장 승인 모델로 전환되며 미사용

    @Enumerated(EnumType.STRING)
    @Column(name = "fallbackApprovalStatus", length = 20)
    private FallbackApprovalStatus fallbackApprovalStatus;   // 예비 폴백 방장 승인 상태(null=폴백 아님)

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 그날 인증 행 개시 (PENDING). 창/확정 시각은 applyWindow로 별도 세팅. */
    public static VerificationDaily open(UUID challengeMemberId, UUID challengeId, UUID userId, LocalDate targetDate) {
        VerificationDaily v = new VerificationDaily();
        v.id = UuidGenerator.generate();
        v.challengeMemberId = challengeMemberId;
        v.challengeId = challengeId;
        v.userId = userId;
        v.targetDate = targetDate;
        v.status = VerificationStatus.PENDING;
        return v;
    }

    /** 인증 창/확정 시각 설정(스케줄링 시점). */
    public void applyWindow(Instant windowClosesAt, Instant finalizeAfter) {
        this.windowClosesAt = windowClosesAt;
        this.finalizeAfter = finalizeAfter;
    }

    /** 종합 판정 반영(평가/확정 시점). 자동 경로는 verifiedVia=AUTO로 마킹. */
    public void recordResult(VerificationStatus status, String method, String failureReason, Instant verifiedAt) {
        this.status = status;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = verifiedAt;
        if (status == VerificationStatus.SUCCESS && this.verifiedVia == null) {
            this.verifiedVia = VerifiedVia.AUTO;
        }
    }

    /** 정규 수동(PHOTO/SELF_CHECK) 확정 — 즉시 SUCCESS. */
    public void recordManual(String method, Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.method = method;
        this.failureReason = null;
        this.verifiedAt = verifiedAt;
        this.verifiedVia = VerifiedVia.MANUAL;
        this.disputeClosesAt = null;
    }

    /**
     * 예비 폴백 제출(§9.2, 방장 승인 모델). 상태는 PENDING 유지(진행률 미반영) + 승인 대기 마킹.
     * 방장이 승인/거절할 때까지 SUCCESS/FAILED 로 확정되지 않는다.
     */
    public void recordFallbackPending(String method) {
        this.status = VerificationStatus.PENDING;
        this.method = method;
        this.failureReason = null;
        this.verifiedVia = null;
        this.verifiedAt = null;
        this.fallbackApprovalStatus = FallbackApprovalStatus.PENDING;
    }

    /** 방장 승인 → SUCCESS 확정(verifiedVia=MANUAL_FALLBACK). */
    public void approveFallback(Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.failureReason = null;
        this.verifiedVia = VerifiedVia.MANUAL_FALLBACK;
        this.verifiedAt = verifiedAt;
        this.fallbackApprovalStatus = FallbackApprovalStatus.APPROVED;
    }

    /** 방장 거절 → FAILED 확정(failureReason=FALLBACK_REJECTED). */
    public void rejectFallback(Instant verifiedAt) {
        this.status = VerificationStatus.FAILED;
        this.failureReason = "FALLBACK_REJECTED";
        this.verifiedVia = null;
        this.verifiedAt = verifiedAt;
        this.fallbackApprovalStatus = FallbackApprovalStatus.REJECTED;
    }

    /** 방장 결정 대기 중인 폴백 제출인지. */
    public boolean isFallbackPendingApproval() {
        return fallbackApprovalStatus == FallbackApprovalStatus.PENDING;
    }

    public boolean isPending() { return status == VerificationStatus.PENDING; }
}