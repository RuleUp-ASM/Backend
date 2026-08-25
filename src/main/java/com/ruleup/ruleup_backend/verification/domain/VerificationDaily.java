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

    /**
     * 이의 제기 창 길이(잠정 실패 전환 시점부터) — 챌린지 생성 및 운영 정책 §7.2 기준 **1일**.
     * 확정 시각은 고정 시각(00시·03시)이 아니라 인증 신호로 판정이 가능해진 때이므로,
     * 창의 시작점도 그 확정 시각이다.
     */
    public static final int OBJECTION_WINDOW_DAYS = 1;

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

    /** 판정 결과 모달을 봤다는 확인(ack) 시각. null이면 today 응답에 unacknowledgedResult로 실린다. */
    @Column(name = "acknowledgedAt")
    private Instant acknowledgedAt;

    // ===== v2: 확정 경로 + 예비 폴백 이의 윈도우 (테크스펙 v2 §9) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "verifiedVia")
    private VerifiedVia verifiedVia;     // AUTO / MANUAL / MANUAL_FALLBACK (없으면 미확정)

    @Column(name = "disputeClosesAt")
    private Instant disputeClosesAt;     // 이의 제기 창 마감(잠정 실패 전환 +1일). 이 시각 후 미제기면 배치가 FAILED lock.

    /** 방 피드에 실패 이벤트를 공유해도 되는 시각. 이의 제기 중에는 반드시 null이다. */
    @Column(name = "shareableAt")
    private Instant shareableAt;

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
        if (status == VerificationStatus.SUCCESS) this.shareableAt = verifiedAt;
        if (status == VerificationStatus.FAILED) this.shareableAt = verifiedAt;
    }

    /** 정규 수동(PHOTO/SELF_CHECK) 확정 — 즉시 SUCCESS. */
    public void recordManual(String method, Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.method = method;
        this.failureReason = null;
        this.verifiedAt = verifiedAt;
        this.verifiedVia = VerifiedVia.MANUAL;
        this.disputeClosesAt = null;
        this.shareableAt = verifiedAt;
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

    /**
     * 폴백 제출 기각(§10.2 v3): 해당 제출만 기각하고 일자 판정은 기존(자동) 경로로 복귀(PENDING).
     * 그날이 실패로 확정되는 것이 아니다 — 자동 신호가 오면 SUCCESS, 끝내 미충족이면 §8.7 잠정 실패 경로.
     */
    public void rejectFallbackSubmission() {
        this.status = VerificationStatus.PENDING;
        this.verifiedVia = null;
        this.verifiedAt = null;
        this.failureReason = null;
        this.fallbackApprovalStatus = FallbackApprovalStatus.REJECTED;
    }

    /** 방장 결정 대기 중인 폴백 제출인지. */
    public boolean isFallbackPendingApproval() {
        return fallbackApprovalStatus == FallbackApprovalStatus.PENDING;
    }

    public boolean isPending() { return status == VerificationStatus.PENDING; }

    // ===== v3: 실패 2단계(잠정 실패 → 이의 제기 창 → 확정) §8.7 =====

    /**
     * 잠정 실패(그룹) 전환. status=FAILED_PROVISIONAL, 이의 제기 창 마감(objectionClosesAt) 설정.
     * 온도는 반영하지 않는다(확정 아님). verifiedAt/verifiedVia는 확정 시점까지 미설정.
     */
    public void recordProvisionalFailure(String method, String failureReason, Instant objectionClosesAt) {
        this.status = VerificationStatus.FAILED_PROVISIONAL;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedVia = null;
        this.verifiedAt = null;
        this.disputeClosesAt = objectionClosesAt;
        this.shareableAt = null;
    }

    /** 잠정 실패 → FAILED 확정(창 종료·미제기/기각). 온도 반영 트리거는 이 시점. failureReason 유지. */
    public void lockFailed(Instant verifiedAt) {
        this.status = VerificationStatus.FAILED;
        this.verifiedAt = verifiedAt;
        this.shareableAt = verifiedAt;
    }

    /** 이의 제기 승인 → SUCCESS 확정(verifiedVia=OBJECTION). 잠정 실패는 온도 미반영이라 복원 불필요. */
    public void approveObjection(Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.failureReason = null;
        this.verifiedVia = VerifiedVia.OBJECTION;
        this.verifiedAt = verifiedAt;
        this.shareableAt = verifiedAt;
    }

    /** 이의 제기 기각 → FAILED 확정(lock, failureReason=OBJECTION_REJECTED). 재제기 불가. */
    public void rejectObjection(Instant verifiedAt) {
        this.status = VerificationStatus.FAILED;
        this.failureReason = "OBJECTION_REJECTED";
        this.verifiedVia = null;
        this.verifiedAt = verifiedAt;
        this.shareableAt = verifiedAt;
    }

    public boolean isProvisionalFailure() { return status == VerificationStatus.FAILED_PROVISIONAL; }

    // ===== 판정 결과 확인(ack) / 수동 인증 취소 =====

    /** 판정 결과 모달을 봤다는 확인. 멱등 — 중복 호출은 첫 확인 시각을 유지한다. */
    public void acknowledge(Instant at) {
        if (this.acknowledgedAt == null) this.acknowledgedAt = at;
    }

    /** 종결(SUCCESS/FAILED)됐지만 아직 확인하지 않은 판정인지 — 모달을 띄울 대상. */
    public boolean hasUnacknowledgedResult() {
        return acknowledgedAt == null
                && (status == VerificationStatus.SUCCESS || status == VerificationStatus.FAILED);
    }

    /** 수동 체크로 확정된 건인지(자동 판정 건은 취소 대상이 아니다). */
    public boolean isManualVerification() { return verifiedVia == VerifiedVia.MANUAL; }

    /**
     * 수동 체크 취소 — 그날을 다시 미확정(PENDING)으로 되돌린다.
     * 성공으로 방 피드에 실린 이벤트도 함께 거둬들인다(shareableAt=null).
     */
    public void cancelManual() {
        this.status = VerificationStatus.PENDING;
        this.method = null;
        this.failureReason = null;
        this.verifiedAt = null;
        this.verifiedVia = null;
        this.shareableAt = null;
        this.acknowledgedAt = null;
    }
}
