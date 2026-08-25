package com.ruleup.ruleup_backend.verification.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.common.verification.VerificationStatus;
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
 * 하루 인증 판정 (VerificationDaily 테이블) — 그날 챌린지 단위 판정 [부모].
 * 방식별 평가(VerificationMethodResult)를 종합한 결과. 인증 정책 §2.
 *
 * <p>저장되는 것은 <b>확정 결과</b>(SUCCESS/FAILED)와 아직 확정되지 않은 자리(PENDING)뿐이다.
 * 진행중·실패 예정·검사중은 저장하지 않고 조회 시 계산한다 —
 * 실패 예정은 PENDING 행에 {@code failureReason} 만 달린 상태로 나타난다.
 *
 * <p>시간 규칙
 * <ul>
 *   <li>성공은 조건 충족 <b>즉시</b> 확정한다.</li>
 *   <li>실패는 {@code finalizeAfter}(= 귀속일 다음 날 00:00 KST)에 확정 배치가 만든다. 그 전에는 만들지 않는다.</li>
 *   <li>확정된 실패는 {@code appealClosesAt} 까지 이의를 받을 수 있고, 그때까지 피드에 공유되지 않는다.</li>
 * </ul>
 *
 * <p>uq(challengeMemberId, targetDate)로 멤버×날짜 하루 1줄 — 동시 sync 가 같은 성공을 발견해도 한 건만 확정된다.
 * challengeId/userId는 조회 최적화용 비정규화(FK 아님). 연관관계 대신 raw UUID만 보유(다른 도메인과 동일 패턴).
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
    private LocalDate targetDate;        // KST 기준 귀속일

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VerificationStatus status = VerificationStatus.PENDING;

    @Column(name = "method", length = 40)
    private String method;               // 판정에 쓰인 방식(들). AND이면 복수 직렬화.

    /** 실패 사유 코드. 확정 전이면 "실패 예정"의 사유, 확정 후면 최종 실패 사유. */
    @Column(name = "failureReason", length = 40)
    private String failureReason;

    @Column(name = "windowClosesAt")
    private Instant windowClosesAt;      // 인증 창 닫힘 시각(시간창이 있는 유형)

    /** 최종 확정 시각 = 귀속일 다음 날 00:00 KST. 확정 배치가 이 시각이 지난 PENDING 행만 처리한다. */
    @Column(name = "finalizeAfter")
    private Instant finalizeAfter;

    @Column(name = "verifiedAt")
    private Instant verifiedAt;          // 확정 시각(미확정이면 null)

    /** 판정 결과 모달을 봤다는 확인(ack) 시각. null이면 today 응답에 unacknowledgedResult로 실린다. */
    @Column(name = "acknowledgedAt")
    private Instant acknowledgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verifiedVia")
    private VerifiedVia verifiedVia;     // AUTO / MANUAL / APPEAL (없으면 미확정)

    /** 이의 신청 기한 — 실패 확정일의 다음 날 00:00 KST. 실패 확정 시에만 열린다. */
    @Column(name = "appealClosesAt")
    private Instant appealClosesAt;

    /** 방 피드에 실패 이벤트를 공유해도 되는 시각. 이의 기간 중에는 반드시 null 이 아니라 기한 이후여야 한다. */
    @Column(name = "shareableAt")
    private Instant shareableAt;

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

    /** 인증 창/확정 시각 설정. finalizeAfter 는 판정 유형과 무관하게 귀속일 다음 날 00:00 KST 다. */
    public void applyWindow(Instant windowClosesAt, Instant finalizeAfter) {
        this.windowClosesAt = windowClosesAt;
        this.finalizeAfter = finalizeAfter;
    }

    /**
     * 종합 판정 반영. 자동 경로는 verifiedVia=AUTO 로 마킹한다.
     *
     * <p>실패(FAILED)는 이 메서드로 만들지 않는다 — 확정 시각·이의 기한·공유 시각을 함께 세워야 해서
     * {@link #confirmFailure(Instant, String, String)} 하나로만 들어온다.
     */
    public void recordResult(VerificationStatus status, String method, String failureReason, Instant verifiedAt) {
        if (status == VerificationStatus.FAILED) {
            throw new IllegalArgumentException("실패 확정은 confirmFailure 로만 만든다");
        }
        this.status = status;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = verifiedAt;
        if (status == VerificationStatus.SUCCESS) {
            if (this.verifiedVia == null) this.verifiedVia = VerifiedVia.AUTO;
            this.appealClosesAt = null;      // 성공은 이의 대상이 아니다
            this.shareableAt = verifiedAt;   // 성공은 즉시 공유 가능
        }
    }

    /**
     * 실패 예정 — 위반이나 목표 미달이 이미 확인됐지만 <b>아직 확정하지 않는다</b>(인증 정책 §2).
     * 상태는 PENDING 그대로 두고 사유만 남긴다. 늦게 도착한 신호로 확정 전까지 되돌릴 수 있어야 하기 때문이다.
     */
    public void recordFailExpected(String method, String failureReason) {
        this.status = VerificationStatus.PENDING;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = null;
        this.verifiedVia = null;
        this.appealClosesAt = null;
        this.shareableAt = null;
    }

    /**
     * 실패 확정 — 귀속일 다음 날 00:00 KST 확정 배치에서만 호출한다.
     * 이의 기한을 열고, 그 기한 전까지는 방 피드에 공유하지 않는다
     * (인용될 수도 있는 실패로 망신을 주지 않기 위한 절대 조건).
     */
    public void confirmFailure(Instant confirmedAt, String method, String failureReason) {
        this.status = VerificationStatus.FAILED;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = confirmedAt;
        this.verifiedVia = null;
        this.appealClosesAt = VerificationDeadlines.appealClosesAt(confirmedAt);
        this.shareableAt = this.appealClosesAt;
    }

    /** 수동 인증 챌린지의 당일 체크 — 즉시 SUCCESS. */
    public void recordManual(String method, Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.method = method;
        this.failureReason = null;
        this.verifiedAt = verifiedAt;
        this.verifiedVia = VerifiedVia.MANUAL;
        this.appealClosesAt = null;
        this.shareableAt = verifiedAt;
    }

    /**
     * 이의 인용 → 완료로 정정. 형식 요건만 통과하면 판정 없이 즉시 이 경로로 들어온다(인증 정책 §5).
     * 정정된 실패는 이후 실패 공유 대상에서 빠진다.
     */
    public void correctByAppeal(Instant acceptedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.failureReason = null;
        this.verifiedVia = VerifiedVia.APPEAL;
        this.verifiedAt = acceptedAt;
        this.appealClosesAt = null;
        this.shareableAt = acceptedAt;
    }

    public boolean isPending() { return status == VerificationStatus.PENDING; }

    /** 더 이상 자동으로 바뀌지 않는 확정 결과인지 — 확정 이후 도착한 신호는 이걸 건드리지 않는다. */
    public boolean isTerminal() { return status.isTerminal(); }

    /** 위반·미달이 이미 확인됐지만 아직 확정되지 않은 상태(= 실패 예정). */
    public boolean isFailExpected() {
        return status == VerificationStatus.PENDING && failureReason != null;
    }

    /** 지금 이의를 받을 수 있는 상태인지 — 실패 확정 + 기한 안. 횟수 한도는 여기서 보지 않는다(없다). */
    public boolean isAppealable(Instant now) {
        return status == VerificationStatus.FAILED
                && appealClosesAt != null && now.isBefore(appealClosesAt);
    }

    // ===== 판정 결과 확인(ack) / 수동 인증 취소 =====

    /** 판정 결과 모달을 봤다는 확인. 멱등 — 중복 호출은 첫 확인 시각을 유지한다. */
    public void acknowledge(Instant at) {
        if (this.acknowledgedAt == null) this.acknowledgedAt = at;
    }

    /** 종결(SUCCESS/FAILED)됐지만 아직 확인하지 않은 판정인지 — 모달을 띄울 대상. */
    public boolean hasUnacknowledgedResult() {
        return acknowledgedAt == null && status.isTerminal();
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
