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
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "creator_id", nullable = false, updatable = false, length = 36)
    private UUID creatorId;

    @Column(name = "title", nullable = false, length = 30)
    private String title;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "participation_type", nullable = false)
    private ParticipationType participationType;

    @Column(name = "min_manner_temperature", precision = 4, scale = 1)
    private BigDecimal minMannerTemperature;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repeat_days", nullable = false)
    private List<String> repeatDays = new ArrayList<>();

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // ===== 루틴(인증) =====
    @Column(name = "template_id")
    private Long templateId;                          // 매칭된 루틴 템플릿(직접 입력이면 null)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_config", nullable = false)
    private VerificationConfig verificationConfig;    // 인증 방식 스냅샷

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();   // 목표값(예: {"distance_km":5})

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "penalty_config", nullable = false)
    private PenaltyConfig penalty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reward_config", nullable = false)
    private RewardConfig reward;

    @Enumerated(EnumType.STRING)
    @Column(name = "anonymity", nullable = false)
    private Anonymity anonymity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChallengeStatus status;

    @Column(name = "ai_assisted", nullable = false)
    private boolean aiAssisted;

    @Column(name = "participant_count", nullable = false)
    private int participantCount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false)
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
        c.aiAssisted = aiAssisted;
        c.participantCount = 0;
        return c;
    }

    public boolean isEditable() { return status.isEditable(); }

    public void changeTitle(String v)        { if (v != null) this.title = v; }
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