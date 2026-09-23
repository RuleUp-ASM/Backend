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
import java.util.Objects;

/**
 * 하루 인증 판정 (VerificationDaily 테이블) — 그날 챌린지 단위 판정 [부모].
 * 방식별 평가(VerificationMethodResult)를 종합한 결과. 인증 정책 §2.
 *
 * <p>저장되는 것은 <b>확정 결과</b>(SUCCESS/FAILED)와 아직 확정되지 않은 자리(PENDING)뿐이다.
 * 진행중·실패 예정·검사중은 저장하지 않고 조회 시 계산한다 —
 * 실패 예정은 PENDING 행에 {@code failureReason} 만 달린 상태로 나타난다.
 *
 * <p>시간 규칙 (인증 정책 §2, {@link VerificationDeadlines})
 * <ul>
 *   <li>성공은 조건 충족 <b>즉시</b> 확정한다.</li>
 *   <li>귀속일이 끝나도 하루(D+1)는 더 기다린다. 그 사이 도착한 신호도 발생 시각이 맞으면 그대로 인정한다.</li>
 *   <li>실패는 {@code finalizeAfter}(= 귀속일 이틀 뒤 00:00 KST)에 확정 배치가 만든다. 그 전에는 만들지 않는다.</li>
 *   <li>이의는 확정 <b>전에</b> 받는다 — {@code appealClosesAt} 이 확정 시각과 같아서,
 *       유저는 유예 하루 동안 "이대로면 실패"를 보고 신청한다.</li>
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

    /**
     * 판정 <b>불가</b> 사유 — {@code PERMISSION_MISSING} / {@code NO_SIGNAL}. 실패 사유와 층이 다르다.
     * 「목표에 못 미쳤다」와 「잴 수가 없었다」는 유저가 할 일이 다르므로 섞지 않는다(공통 5-2).
     */
    @Column(name = "gapReason", length = 30)
    private String gapReason;

    @Column(name = "windowClosesAt")
    private Instant windowClosesAt;      // 인증 창 닫힘 시각(시간창이 있는 유형)

    /** 최종 확정 시각 = 귀속일 이틀 뒤 00:00 KST. 확정 배치가 이 시각이 지난 PENDING 행만 처리한다. */
    @Column(name = "finalizeAfter")
    private Instant finalizeAfter;

    /** 일시적 처리 실패의 재시도 시각. 정책상 확정 기한과 별도로 관리한다. */
    @Column(name = "finalizeRetryAt")
    private Instant finalizeRetryAt;

    @Column(name = "verifiedAt")
    private Instant verifiedAt;          // 확정 시각(미확정이면 null)

    /** 판정 결과 모달을 봤다는 확인(ack) 시각. null이면 today 응답에 unacknowledgedResult로 실린다. */
    @Column(name = "acknowledgedAt")
    private Instant acknowledgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verifiedVia")
    private VerifiedVia verifiedVia;     // AUTO / MANUAL / APPEAL (없으면 미확정)

    /** 이의 신청 기한 = 확정 시각(D+2 00:00 KST). 행을 여는 시점에 함께 세워 확정 전에도 신청받는다. */
    @Column(name = "appealClosesAt")
    private Instant appealClosesAt;

    /** 방 피드에 실패 이벤트를 공유해도 되는 시각. 실패는 확정(= 이의 마감) 이후에만 실린다. */
    @Column(name = "shareableAt")
    private Instant shareableAt;

    /**
     * 낙관적 락. 확정 배치는 FOR UPDATE 로 행을 선점하지만 일반 sync 는 잠금 없이 같은 행을 갱신한다 —
     * 배치가 실패를 확정하는 사이 흘러들어온 sync 가 그 확정을 덮어써 되돌리는 일(lost update)을 막는다.
     * "확정 결과가 자동으로 뒤집히지 않는다"는 절대 조건을 애플리케이션 로직이 아니라 DB 가 지키게 한다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * <b>판정이 바뀐 횟수.</b> 점수 도메인이 「이 판정을 이미 반영했는가」를 묻는 기준이다.
     *
     * <p>{@link #version} 을 쓰면 안 된다. 그 값은 낙관적 락의 것이라 판정과 무관한 갱신에도
     * 오른다 — 사용자가 결과 모달을 확인하기만 해도({@code acknowledge}) 올라간다. 그러면 점수
     * 동기화가 「새 판정이 왔다」로 읽고 그 시각 이후 원장을 통째로 되감았다가 <b>똑같은 값</b>으로
     * 다시 쌓는다. 화면에는 CYCLE_FAIL -1 과 APPEAL_RESTORE +1 이 짝을 지어 늘어난다(QA TIER-05 · TIER-15).
     *
     * <p>그래서 상태를 바꾸는 자리에서만 올린다. 모달 확인·확정 연기처럼 판정을 건드리지 않는
     * 갱신은 이 값을 움직이지 않는다.
     */
    @Column(name = "scoreVersion", nullable = false)
    private long scoreVersion;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    /** 그날 인증 행 개시 (PENDING). 정책 기한은 즉시 세우고 인증 창만 평가 후 보강한다. */
    public static VerificationDaily open(UUID challengeMemberId, UUID challengeId, UUID userId, LocalDate targetDate) {
        VerificationDaily v = new VerificationDaily();
        v.id = UuidGenerator.generate();
        v.challengeMemberId = challengeMemberId;
        v.challengeId = challengeId;
        v.userId = userId;
        v.targetDate = targetDate;
        v.status = VerificationStatus.PENDING;
        v.applyWindow(null);
        return v;
    }

    /**
     * 인증 창 표시 시각 설정. 확정·이의 마감은 귀속일만으로 정해지므로 함께 세운다 —
     * 판정 결과나 평가기 사정에 따라 흔들리면 안 된다.
     */
    public void applyWindow(Instant windowClosesAt) {
        this.windowClosesAt = windowClosesAt;
        this.finalizeAfter = VerificationDeadlines.finalizeAfter(targetDate);
        if (!status.isTerminal()) {
            this.appealClosesAt = VerificationDeadlines.appealClosesAt(targetDate);
        }
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
        this.scoreVersion++;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = verifiedAt;
        if (status != VerificationStatus.PENDING) this.finalizeRetryAt = null;
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
        this.scoreVersion++;
        this.method = method;
        this.failureReason = failureReason;
        this.verifiedAt = null;
        this.verifiedVia = null;
        this.shareableAt = null;
        // appealClosesAt 은 건드리지 않는다 — 귀속일만으로 정해지고, 이의는 이 상태에서 받는다.
        if (this.appealClosesAt == null) {
            this.appealClosesAt = VerificationDeadlines.appealClosesAt(targetDate);
        }
    }

    /**
     * 실패 확정 — 귀속일 이틀 뒤 00:00 KST 확정 배치에서만 호출한다.
     *
     * <p>이 시점에 이의 기한은 이미 닫혀 있다(같은 시각). 확정 전 유예 하루 동안 이의를 받았으므로,
     * 여기까지 온 실패는 인용될 여지가 없어 바로 피드에 공유해도 된다.
     */
    public void confirmFailure(Instant confirmedAt, String method, String failureReason) {
        Objects.requireNonNull(confirmedAt, "실패 확정 시각이 필요하다");
        if (!VerificationDeadlines.finalizeDue(targetDate, confirmedAt)) {
            throw new IllegalArgumentException("유예 기간이 끝나기 전에는 실패로 확정할 수 없다");
        }
        this.status = VerificationStatus.FAILED;
        this.scoreVersion++;
        this.method = method;
        this.failureReason = failureReason;
        this.gapReason = GapReason.of(failureReason);
        this.verifiedAt = confirmedAt;
        this.verifiedVia = null;
        this.shareableAt = confirmedAt;
        this.finalizeRetryAt = null;
    }

    /** 우회 쓰기나 새 코드 경로도 불완전한 실패/기한을 JPA로 저장할 수 없다. */
    @PrePersist
    @PreUpdate
    private void validateIntegrity() {
        Instant deadline = VerificationDeadlines.finalizeAfter(targetDate);
        if (!deadline.equals(finalizeAfter)
                || (appealClosesAt != null && !deadline.equals(appealClosesAt))) {
            throw new IllegalStateException("인증 기한은 귀속일 D+2 00:00 KST여야 한다");
        }
        if (hasInvalidFailure()) {
            throw new IllegalStateException("실패 판정에는 유예 종료 이후의 확정·공유 시각이 필요하다");
        }
    }

    public boolean hasInvalidFailure() {
        return status == VerificationStatus.FAILED && (verifiedAt == null || shareableAt == null
                || verifiedAt.isBefore(VerificationDeadlines.finalizeAfter(targetDate))
                || shareableAt.isBefore(verifiedAt));
    }

    /** 수동 인증 챌린지의 당일 체크 — 즉시 SUCCESS. */
    public void recordManual(String method, Instant verifiedAt) {
        this.status = VerificationStatus.SUCCESS;
        this.scoreVersion++;
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
        this.scoreVersion++;
        this.failureReason = null;
        this.verifiedVia = VerifiedVia.APPEAL;
        this.verifiedAt = acceptedAt;
        this.appealClosesAt = null;
        this.shareableAt = acceptedAt;
    }

    public boolean isPending() { return status == VerificationStatus.PENDING; }

    /** 더 이상 자동으로 바뀌지 않는 확정 결과인지 — 확정 이후 도착한 신호는 이걸 건드리지 않는다. */
    public boolean isTerminal() { return status.isTerminal(); }

    /**
     * "이대로 가면 실패"인지 — 계산 상태다.
     * 판정 방향(도달형/제약형)에 따라 갈리므로 {@link FailExpectation} 에 위임한다.
     */
    public boolean isFailExpected(Polarity polarity, Instant now) {
        return FailExpectation.isExpected(status, targetDate, failureReason, polarity, now);
    }

    /**
     * 지금 이의를 받을 수 있는지 — 실패 예정이거나 이미 실패 확정이고, 기한(= 확정 시각) 안일 때.
     * 횟수 한도는 여기서 보지 않는다(없다).
     */
    public boolean isAppealable(Polarity polarity, Instant now) {
        if (appealClosesAt == null || !now.isBefore(appealClosesAt)) return false;
        return status == VerificationStatus.FAILED || isFailExpected(polarity, now);
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
        this.scoreVersion++;
        this.method = null;
        this.failureReason = null;
        this.verifiedAt = null;
        this.verifiedVia = null;
        this.shareableAt = null;
        this.acknowledgedAt = null;
    }
}
