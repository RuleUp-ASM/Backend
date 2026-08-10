package com.ruleup.ruleup_backend.challenge.domain;

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
import com.ruleup.ruleup_backend.common.verification.ScheduleType;
import com.ruleup.ruleup_backend.common.verification.PeriodUnit;
import com.ruleup.ruleup_backend.common.verification.SetupStatus;
import com.ruleup.ruleup_backend.common.verification.GeoAnchor;
import com.ruleup.ruleup_backend.common.verification.ScreenApp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
@Table(name = "challenge_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeMember extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "challenge_id", nullable = false, updatable = false)
    private UUID challengeId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MemberStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;            // 참여(또는 신청) 시각

    // ===== 진행률 비정규화 (인증 스펙 §4.2) — 인증 sync·확정 배치가 유지 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false)
    private ScheduleType scheduleType = ScheduleType.FIXED_DAYS;

    @Column(name = "target_days", nullable = false)
    private int targetDays = 0;          // 전체 대상일(빈도형: 필요 횟수 ΣN)

    @Column(name = "success_days", nullable = false)
    private int successDays = 0;

    @Column(name = "fail_days", nullable = false)
    private int failDays = 0;

    @Column(name = "progress_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal progressRate = BigDecimal.ZERO;   // 진행률(%)

    @Enumerated(EnumType.STRING)
    @Column(name = "today_status")
    private VerificationStatus todayStatus;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    // --- 빈도형(FREQUENCY) 전용 주기 카운터 ---
    @Enumerated(EnumType.STRING)
    @Column(name = "period_unit")
    private PeriodUnit periodUnit;

    @Column(name = "period_target")
    private Integer periodTarget;        // 주기당 N

    @Column(name = "cur_period_start")
    private LocalDate curPeriodStart;

    @Column(name = "cur_period_end")
    private LocalDate curPeriodEnd;

    @Column(name = "cur_period_completed")
    private Integer curPeriodCompleted;

    // ===== v2: 셋업 상태 + 멤버 바인딩 앵커(PER_MEMBER) + 예비 폴백 카운터 (테크스펙 v2 §4·§5·§9) =====
    @Enumerated(EnumType.STRING)
    @Column(name = "setup_status", nullable = false)
    private SetupStatus setupStatus = SetupStatus.PENDING_SETUP;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anchors")
    private List<GeoAnchor> anchors;          // 멤버 GeoAnchor[] (없으면 null). config가 아니라 멤버에 저장.

    @Column(name = "anchor_updated_at")
    private Instant anchorUpdatedAt;          // 수정 쿨다운 기준

    // ===== SCREEN_TIME 측정 대상 앱(PER_MEMBER 바인딩, my-screen-apps API) =====
    // 현재 적용 세트 + 익일 적용 대기 세트(pending). 변경은 항상 익일 00:00부터 적용(당일 조작 방지).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "screen_apps")
    private List<ScreenApp> screenApps;               // 현재 적용 중인 세트(없으면 null)

    @Column(name = "screen_apps_applied_from")
    private Instant screenAppsAppliedFrom;            // 현재 세트 적용 시작 시각

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_screen_apps")
    private List<ScreenApp> pendingScreenApps;        // 익일 적용 대기 세트(없으면 null)

    @Column(name = "pending_screen_apps_effective_date")
    private LocalDate pendingScreenAppsEffectiveDate; // 대기 세트 적용 시작 날짜(익일)

    @Column(name = "screen_apps_updated_at")
    private Instant screenAppsUpdatedAt;             // 변경 쿨다운 기준(마지막 stage 시각)

    @Column(name = "fallback_used_period_start")
    private LocalDate fallbackUsedPeriodStart;// 예비 폴백 주1회(롤링 7일) 윈도우 시작

    @Column(name = "fallback_used_count", nullable = false)
    private int fallbackUsedCount = 0;

    /**
     * 셋업 미완료 고스트(무음) 푸시를 마지막으로 보낸 시각. 재발송 쿨다운 기준(스팸 방지).
     * NULL = 아직 보낸 적 없음. 셋업이 READY 되면 더는 대상이 아니라 이 값은 자연히 의미를 잃는다.
     */
    @Column(name = "ghost_pushed_at")
    private Instant ghostPushedAt;

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

    // 참여/재참여/탈퇴 상태 전이는 동시성 안전을 위해
    // ChallengeMemberRepository.compareAndSetStatus(CAS)로 처리한다(엔티티 직접 변경 X).

    public boolean isPending() { return status == MemberStatus.PENDING; }
    public boolean isActive()  { return status == MemberStatus.ACTIVE; }
    public boolean isOwner()   { return role == MemberRole.OWNER; }
    public boolean isManager() { return role == MemberRole.MANAGER; }

    /** 역할 변경(임명/해제 §7-1, 위임 role swap §7-2). OWNER 정확히 1명 불변식은 호출부가 보장. */
    public void changeRole(MemberRole role) { this.role = role; }

    /** 탈퇴(§6): ACTIVE → LEFT. 재참여 금지를 위해 행은 남긴다(uq_member). */
    public void leave() { this.status = MemberStatus.LEFT; }

    // ===== 인증 진행률 비정규화 갱신 (sync·배치) =====
    public void setupFixedDays(int targetDays) {
        this.scheduleType = ScheduleType.FIXED_DAYS;
        this.targetDays = targetDays;
    }

    public void setupFrequency(PeriodUnit unit, int periodTarget, LocalDate curStart,
                               LocalDate curEnd, int targetDays) {
        this.scheduleType = ScheduleType.FREQUENCY;
        this.periodUnit = unit;
        this.periodTarget = periodTarget;
        this.curPeriodStart = curStart;
        this.curPeriodEnd = curEnd;
        this.curPeriodCompleted = 0;
        this.targetDays = targetDays;
    }

    public void applyProgress(int successDays, int failDays, BigDecimal progressRate,
                              VerificationStatus todayStatus,
                              Instant lastSyncedAt) {
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
    public void applyCounts(int successDays, int failDays, BigDecimal progressRate) {
        this.successDays = successDays;
        this.failDays = failDays;
        this.progressRate = progressRate;
    }

    /** 빈도형 주기 롤오버: 미달분 정산 + 다음 주기로. */
    public void rolloverPeriod(LocalDate nextStart, LocalDate nextEnd, int shortfall) {
        this.failDays += shortfall;
        this.curPeriodStart = nextStart;
        this.curPeriodEnd = nextEnd;
        this.curPeriodCompleted = 0;
    }

    // ===== v2: 셋업 / 앵커 / 폴백 =====

    /** 최초 진입 셋업 완료 → READY(평가 대상 진입). */
    public void markSetupReady() { this.setupStatus = SetupStatus.READY; }

    /** 셋업 유도 고스트 푸시 발송 기록(쿨다운 기준 갱신). */
    public void markGhostPushed(Instant at) { this.ghostPushedAt = at; }

    public boolean isSetupReady() { return setupStatus == SetupStatus.READY; }

    /** 멤버 앵커 교체(셋업/내 위치 수정). 본인 것만 바뀐다(§5.1). */
    public void replaceAnchors(List<GeoAnchor> newAnchors, Instant at) {
        this.anchors = (newAnchors != null) ? new ArrayList<>(newAnchors) : null;
        this.anchorUpdatedAt = at;
    }

    // ===== SCREEN_TIME 측정 대상 앱 =====

    /** 최초 설정: 대기 없이 즉시 현재 세트로 적용(보호할 이전 세트가 없음). */
    public void setScreenAppsInitial(List<ScreenApp> apps, Instant at) {
        this.screenApps = (apps != null) ? new ArrayList<>(apps) : null;
        this.screenAppsAppliedFrom = at;
        this.pendingScreenApps = null;
        this.pendingScreenAppsEffectiveDate = null;
        this.screenAppsUpdatedAt = at;
    }

    /** 변경 접수: 익일 00:00부터 적용될 대기 세트로 stage. 같은 날 재요청은 대기 세트를 덮어쓴다(마지막 승리). */
    public void stagePendingScreenApps(List<ScreenApp> apps, LocalDate effectiveDate, Instant at) {
        this.pendingScreenApps = (apps != null) ? new ArrayList<>(apps) : null;
        this.pendingScreenAppsEffectiveDate = effectiveDate;
        this.screenAppsUpdatedAt = at;
    }

    /**
     * 대기 세트의 적용일이 도래(effectiveDate ≤ today)했으면 현재 세트로 승격(persist 대상).
     * @return 승격이 일어났으면 true.
     */
    public boolean promoteScreenAppsIfDue(LocalDate today, ZoneId zone) {
        if (pendingScreenAppsEffectiveDate == null || today.isBefore(pendingScreenAppsEffectiveDate)) {
            return false;
        }
        this.screenApps = this.pendingScreenApps;
        this.screenAppsAppliedFrom = pendingScreenAppsEffectiveDate.atStartOfDay(zone).toInstant();
        this.pendingScreenApps = null;
        this.pendingScreenAppsEffectiveDate = null;
        return true;
    }

    /** 대기 세트가 아직 미래(익일 이후) 적용 대기 중인가. */
    public boolean hasPendingScreenApps(LocalDate today) {
        return pendingScreenAppsEffectiveDate != null && today.isBefore(pendingScreenAppsEffectiveDate);
    }

    /** {@code today} 시점에 실제 적용되는 세트(도래한 대기 세트를 반영, persist 없이 계산만). 없으면 빈 리스트. */
    public List<ScreenApp> effectiveScreenApps(LocalDate today) {
        if (pendingScreenAppsEffectiveDate != null && !today.isBefore(pendingScreenAppsEffectiveDate)) {
            return (pendingScreenApps != null) ? pendingScreenApps : List.of();
        }
        return (screenApps != null) ? screenApps : List.of();
    }

    /**
     * 예비 폴백 한도 소진 시도(월 N회·달력 월, §10.2). 달이 바뀌면 리셋 후 허용.
     * @return true=사용 허용(카운터 반영됨) / false=한도 초과
     */
    public boolean tryUseFallback(LocalDate today, int monthlyLimit) {
        LocalDate monthStart = today.withDayOfMonth(1);
        if (fallbackUsedPeriodStart == null || !fallbackUsedPeriodStart.equals(monthStart)) {
            this.fallbackUsedPeriodStart = monthStart;   // 새 달 윈도우 개시
            this.fallbackUsedCount = 0;
        }
        if (fallbackUsedCount >= monthlyLimit) return false;
        this.fallbackUsedCount++;
        return true;
    }
}