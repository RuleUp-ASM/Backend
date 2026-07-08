package com.ruleup.ruleup_backend.challenge.domain;

import com.ruleup.ruleup_backend.common.AssignedIdEntity;
import com.ruleup.ruleup_backend.common.UuidGenerator;
import com.ruleup.ruleup_backend.routine.domain.VerificationConfig;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "Challenge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "creatorId", nullable = false, updatable = false)
    private UUID creatorId;

    @Column(name = "title", nullable = false, length = 30)
    private String title;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "imageUrl", length = 500)
    private String imageUrl;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "participationType", nullable = false)
    private ParticipationType participationType;

    @Column(name = "minMannerTemperature", precision = 4, scale = 1)
    private BigDecimal minMannerTemperature;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repeatDays", nullable = false)
    private List<String> repeatDays = new ArrayList<>();

    @Column(name = "durationDays", nullable = false)
    private Integer durationDays;

    @Column(name = "startDate", nullable = false)
    private LocalDate startDate;

    @Column(name = "endDate", nullable = false)
    private LocalDate endDate;

    // ===== 루틴(인증) =====
    @Column(name = "templateId")
    private Long templateId;                          // 매칭된 루틴 템플릿(직접 입력이면 null)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verificationConfig", nullable = false)
    private VerificationConfig verificationConfig;    // 인증 방식 스냅샷

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();   // 목표값(예: {"distance_km":5})

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "penaltyConfig", nullable = false)
    private PenaltyConfig penalty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rewardConfig", nullable = false)
    private RewardConfig reward;

    @Enumerated(EnumType.STRING)
    @Column(name = "anonymity", nullable = false)
    private Anonymity anonymity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChallengeStatus status;

    // ===== 모더레이션(가시성) 게이트 (§5.1) — lifecycle status 와 독립 축 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "moderationStatus", nullable = false)
    private ChallengeModerationStatus moderationStatus;

    @Column(name = "moderationDecidedAt")
    private Instant moderationDecidedAt;       // APPROVED/REJECTED 확정 시각

    @Column(name = "fixDeadline")
    private Instant fixDeadline;               // REJECTED 1시간 수정창 마감(§5.1)

    @Column(name = "aiAssisted", nullable = false)
    private boolean aiAssisted;

    @Column(name = "participantCount", nullable = false)
    private int participantCount;

    @Column(name = "deletedAt")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "createdAt", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    private static LocalDate deriveEndDate(LocalDate start, int durationDays) {
        return start.plusDays((long) durationDays - 1);
    }

    public static Challenge create(UUID creatorId, String title, String description, String imageUrl,
                                   String category, ParticipationType participationType,
                                   BigDecimal minMannerTemperature, List<String> repeatDays,
                                   int durationDays, LocalDate startDate,
                                   Long templateId, VerificationConfig verificationConfig,
                                   Map<String, Object> params,
                                   PenaltyConfig penalty, RewardConfig reward,
                                   Anonymity anonymity, boolean aiAssisted) {
        Challenge c = new Challenge();
        c.id = UuidGenerator.generate();
        c.creatorId = creatorId;
        c.title = title;
        c.description = description;
        c.imageUrl = imageUrl;
        c.category = category;
        c.participationType = participationType;
        c.minMannerTemperature = (participationType == ParticipationType.GROUP) ? minMannerTemperature : null;
        c.repeatDays = (repeatDays != null) ? new ArrayList<>(repeatDays) : new ArrayList<>();
        c.durationDays = durationDays;
        c.startDate = startDate;
        c.endDate = deriveEndDate(startDate, durationDays);
        c.templateId = templateId;
        c.verificationConfig = verificationConfig;
        c.params = (params != null) ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        c.penalty = penalty;
        c.reward = reward;
        c.anonymity = anonymity;
        c.status = ChallengeStatus.RECRUITING;
        // 가시성 게이트는 "이미지" 기준(§5.1): 이미지가 있으면 비동기 SafeSearch 검수 후 공개(PENDING_REVIEW),
        // 이미지가 없으면 검수 대상이 없어 즉시 공개(APPROVED). 텍스트(이름)는 생성 시 blocklist(동기) +
        // 추천 시 draft 게이트로 거른다(별도 이름 LLM 검수 없음).
        boolean hasImage = imageUrl != null && !imageUrl.isBlank();
        c.moderationStatus = hasImage
                ? ChallengeModerationStatus.PENDING_REVIEW
                : ChallengeModerationStatus.APPROVED;
        c.aiAssisted = aiAssisted;
        c.participantCount = 0;
        return c;
    }

    public boolean isEditable() { return status.isEditable(); }

    /**
     * 시작일 도달 → 진행 개시(§5.7). RECRUITING 일 때만 ACTIVE 로 전환(멱등·방어).
     * ACTIVE 가 되어야 인증 sync(§3.1)가 평가 대상으로 삼는다(VerificationSyncService).
     */
    public void activate() {
        if (this.status == ChallengeStatus.RECRUITING) {
            this.status = ChallengeStatus.ACTIVE;
        }
    }

    /**
     * 종료일 경과 → 종료(§5.5). ACTIVE 일 때만 COMPLETED 로 전환(멱등·방어).
     * 완주율 집계·정산은 인증(VF)/평판 스펙 소관 — 여기선 lifecycle 상태만 마감한다.
     */
    public void complete() {
        if (this.status == ChallengeStatus.ACTIVE) {
            this.status = ChallengeStatus.COMPLETED;
        }
    }

    // ===== 모더레이션 게이트 (§5.1) =====
    public boolean isApproved() { return moderationStatus == ChallengeModerationStatus.APPROVED; }

    /** 조회 가시성: OWNER는 항상, 그 외는 APPROVED만(아니면 호출부에서 404 처리). */
    public boolean isVisibleTo(UUID viewerId) { return isOwner(viewerId) || isApproved(); }

    /** 검수 통과 → 공개·가입 허용. */
    public void approveModeration(Instant at) {
        this.moderationStatus = ChallengeModerationStatus.APPROVED;
        this.moderationDecidedAt = at;
        this.fixDeadline = null;
    }

    /** 검수 거절 → 1시간 수정창 부여(§5.1). 미수정·경과 시 배치가 영구 닫는다. */
    public void rejectModeration(Instant at, Instant fixDeadline) {
        this.moderationStatus = ChallengeModerationStatus.REJECTED;
        this.moderationDecidedAt = at;
        this.fixDeadline = fixDeadline;
    }

    /** title/imageUrl 변경 시 재검수를 위해 PENDING_REVIEW로 되돌린다(§5.1). */
    public void resubmitModeration() {
        this.moderationStatus = ChallengeModerationStatus.PENDING_REVIEW;
        this.moderationDecidedAt = null;
        this.fixDeadline = null;
    }

    public void changeTitle(String v)        { if (v != null) this.title = v; }
    public void changeImageUrl(String v)     { if (v != null) this.imageUrl = v; }
    public void changeDescription(String v)  { this.description = v; }
    public void changeCategory(String v)     { if (v != null) this.category = v; }
    public void changeRepeatDays(List<String> v) { if (v != null) this.repeatDays = new ArrayList<>(v); }
    public void changeParams(Map<String, Object> v) { if (v != null) this.params = new LinkedHashMap<>(v); }
    public void changePenalty(PenaltyConfig v) { if (v != null) this.penalty = v; }
    public void changeReward(RewardConfig v)   { if (v != null) this.reward = v; }
    public void changeMinMannerTemperature(BigDecimal v) {
        if (v != null && participationType == ParticipationType.GROUP) this.minMannerTemperature = v;
    }

    public void changeSchedule(Integer durationDays, LocalDate startDate) {
        if (durationDays != null) this.durationDays = durationDays;
        if (startDate != null)    this.startDate = startDate;
        if (durationDays != null || startDate != null) {
            this.endDate = deriveEndDate(this.startDate, this.durationDays);
        }
    }

    public void increaseParticipantCount() { this.participantCount++; }
    public void decreaseParticipantCount() { if (this.participantCount > 0) this.participantCount--; }
    public void softDelete() { this.deletedAt = Instant.now(); }
    public boolean isOwner(UUID userId) { return this.creatorId.equals(userId); }
    public boolean isGroup() { return participationType == ParticipationType.GROUP; }
}