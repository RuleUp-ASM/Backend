package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.verification.domain.VerificationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import com.ruleup.ruleup_backend.verification.domain.ScheduleType;
import com.ruleup.ruleup_backend.verification.domain.PeriodUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 챌린지 멤버십 (ChallengeMember 테이블). 챌린지 1개 × 사용자 1명 = 1행.
 *  - uqMember(challengeId, userId)로 한 챌린지 1회 멤버십 (스펙 5 재참여).
 *  - 생성자는 챌린지 생성 시 OWNER/ACTIVE로 함께 등록.
 *  - 참여 신청: 솔로/기준미설정 → ACTIVE 즉시, 그룹+기준 → PENDING(운영자 승인 대기).
 *  - 진행률(scheduleType~periodsMet)은 인증 sync·확정 배치가 유지하는 비정규화 필드(인증 스펙 §4.2).
 *    멤버 생성 시엔 기본값(FIXED_DAYS·0)으로 시작하고, 인증 단계에서 실제 스케줄로 세팅·갱신.
 * 연관관계 대신 challengeId/userId만 보유(다른 도메인과 동일 패턴).
 */
@Entity
@Table(name = "ChallengeMember")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeMember extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challengeId", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "userId", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "joinedAt", nullable = false, updatable = false)
    private Instant joinedAt;            // 참여(또는 신청) 시각

    // ===== 진행률 비정규화 (인증 스펙 §4.2) — 인증 sync·확정 배치가 유지 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "scheduleType", nullable = false)
    private ScheduleType scheduleType = ScheduleType.FIXED_DAYS;

    @Column(name = "targetDays", nullable = false)
    private int targetDays = 0;          // 전체 대상일(빈도형: 필요 횟수 ΣN)

    @Column(name = "successDays", nullable = false)
    private int successDays = 0;

    @Column(name = "failDays", nullable = false)
    private int failDays = 0;

    @Column(name = "progressRate", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressRate = BigDecimal.ZERO;   // 진행률(%)

    @Enumerated(EnumType.STRING)
    @Column(name = "todayStatus")
    private VerificationStatus todayStatus;

    @Column(name = "lastSyncedAt")
    private Instant lastSyncedAt;

    // --- 빈도형(FREQUENCY) 전용 주기 카운터 ---
    @Enumerated(EnumType.STRING)
    @Column(name = "periodUnit")
    private PeriodUnit periodUnit;

    @Column(name = "periodTarget")
    private Integer periodTarget;        // 주기당 N

    @Column(name = "curPeriodStart")
    private LocalDate curPeriodStart;

    @Column(name = "curPeriodEnd")
    private LocalDate curPeriodEnd;

    @Column(name = "curPeriodCompleted")
    private Integer curPeriodCompleted;

    @Column(name = "periodsTotal")
    private Integer periodsTotal;

    @Column(name = "periodsMet")
    private Integer periodsMet;

    private static ChallengeMember of(UUID challengeId, UUID userId, MemberRole role, MemberStatus status) {
        ChallengeMember m = new ChallengeMember();
        m.id = UuidGenerator.generate();
        m.challengeId = challengeId;
        m.userId = userId;
        m.role = role;
        m.status = status;
        return m;
    }

    /** 생성자 등록: OWNER + 즉시 ACTIVE */
    public static ChallengeMember owner(UUID challengeId, UUID userId) {
        return of(challengeId, userId, MemberRole.OWNER, MemberStatus.ACTIVE);
    }

    /** 일반 참여: 솔로/기준미설정이면 ACTIVE, 그룹+기준이면 PENDING */
    public static ChallengeMember join(UUID challengeId, UUID userId, MemberStatus initialStatus) {
        return of(challengeId, userId, MemberRole.MEMBER, initialStatus);
    }

    public void approve() { this.status = MemberStatus.ACTIVE; }
    public void reject()  { this.status = MemberStatus.REMOVED; }
    public void leave()   { this.status = MemberStatus.LEFT; }

    /** 탈퇴/거절(LEFT·REMOVED) 후 재참여 신청 → PENDING 복귀 (스펙 5: 재참여는 status 갱신으로 처리). */
    public void rejoinAsPending() { this.status = MemberStatus.PENDING; }

    public boolean isPending() { return status == MemberStatus.PENDING; }
    public boolean isActive()  { return status == MemberStatus.ACTIVE; }

    // ===== 인증 진행률 비정규화 갱신 (sync·배치) =====
    public void setupFixedDays(int targetDays) {
        this.scheduleType = ScheduleType.FIXED_DAYS;
        this.targetDays = targetDays;
    }

    public void setupFrequency(PeriodUnit unit, int periodTarget, java.time.LocalDate curStart,
                               java.time.LocalDate curEnd, int periodsTotal, int targetDays) {
        this.scheduleType = ScheduleType.FREQUENCY;
        this.periodUnit = unit;
        this.periodTarget = periodTarget;
        this.curPeriodStart = curStart;
        this.curPeriodEnd = curEnd;
        this.curPeriodCompleted = 0;
        this.periodsTotal = periodsTotal;
        this.periodsMet = 0;
        this.targetDays = targetDays;
    }

    public void applyProgress(int successDays, int failDays, java.math.BigDecimal progressRate,
                              com.ruleup.ruleup_backend.verification.domain.VerificationStatus todayStatus,
                              java.time.Instant lastSyncedAt) {
        this.successDays = successDays;
        this.failDays = failDays;
        this.progressRate = progressRate;
        this.todayStatus = todayStatus;
        this.lastSyncedAt = lastSyncedAt;
    }

    public void incrementPeriodCompleted() {
        this.curPeriodCompleted = (this.curPeriodCompleted == null ? 0 : this.curPeriodCompleted) + 1;
    }

    /** 진행률 카운터만 갱신(확정 배치 — todayStatus·lastSyncedAt 안 건드림). */
    public void applyCounts(int successDays, int failDays, java.math.BigDecimal progressRate) {
        this.successDays = successDays;
        this.failDays = failDays;
        this.progressRate = progressRate;
    }

    /** 빈도형 주기 롤오버: 미달분 정산 + 다음 주기로. */
    public void rolloverPeriod(java.time.LocalDate nextStart, java.time.LocalDate nextEnd, int shortfall, boolean met) {
        this.failDays += shortfall;
        if (met) this.periodsMet = (this.periodsMet == null ? 0 : this.periodsMet) + 1;
        this.curPeriodStart = nextStart;
        this.curPeriodEnd = nextEnd;
        this.curPeriodCompleted = 0;
    }
}