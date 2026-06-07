package com.ruleup.ruleup_backend.challenge.domain;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 챌린지 (challenges 테이블).
 *  - 생성은 정적 팩토리 create(...)로만 (id 자동 채움, 상태 RECRUITING 고정).
 *  - 가변 설정(인증/패널티/보상/반복요일)은 JSON 컬럼, 검색·필터 키(category 등)는 스칼라 컬럼 (스펙 2.4).
 *  - endDate는 startDate + durationDays 로 서버가 파생(deriveEndDate).
 *  - 수정/삭제는 시작 전(RECRUITING)에만 (스펙 2.5).
 *  - participant_count는 ACTIVE 멤버 수의 비정규화 캐시 (참여/탈퇴 시 갱신, 스펙 2.7).
 */
@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge extends AssignedIdEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)                 // UUID v7 → CHAR(36) (users.id와 동일 전략)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "creator_id", nullable = false, updatable = false, length = 36)
    private UUID creatorId;                      // 생성자(OWNER) user.id. 연관관계 대신 ID만 보유.

    @Column(name = "title", nullable = false, length = 30)
    private String title;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "category", nullable = false, length = 20)
    private String category;                     // InterestCategory 코드 (예: WAKE_UP)

    @Enumerated(EnumType.STRING)
    @Column(name = "participation_type", nullable = false)
    private ParticipationType participationType;

    @Column(name = "min_manner_temperature", precision = 4, scale = 1)
    private BigDecimal minMannerTemperature;     // 그룹만. 솔로는 null.

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repeat_days", nullable = false)
    private List<String> repeatDays = new ArrayList<>();

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;                   // 서버 파생 (startDate + durationDays - 1)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_methods", nullable = false)
    private List<String> verificationMethods = new ArrayList<>();

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
    private boolean aiAssisted;                  // 추천 API를 거쳐 생성됐는지(분석용 플래그)

    @Column(name = "participant_count", nullable = false)
    private int participantCount;                // ACTIVE 멤버 수 (비정규화 캐시)

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** endDate = startDate + (durationDays - 1). 예: 2026-06-01 시작 14일 → 2026-06-14 종료. */
    private static LocalDate deriveEndDate(LocalDate start, int durationDays) {
        return start.plusDays((long) durationDays - 1);
    }

    public static Challenge create(UUID creatorId, String title, String description, String imageUrl,
                                   String category, ParticipationType participationType,
                                   BigDecimal minMannerTemperature, List<String> repeatDays,
                                   int durationDays, LocalDate startDate,
                                   List<String> verificationMethods,
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
        c.verificationMethods = (verificationMethods != null) ? new ArrayList<>(verificationMethods) : new ArrayList<>();
        c.penalty = penalty;
        c.reward = reward;
        c.anonymity = anonymity;
        c.status = ChallengeStatus.RECRUITING;     // 생성 직후 고정
        c.aiAssisted = aiAssisted;
        c.participantCount = 0;
        return c;
    }

    /** 시작 전(RECRUITING)에만 수정 가능한지 */
    public boolean isEditable() {
        return status.isEditable();
    }

    // ===== 부분 수정 (PATCH, null이면 변경 안 함) =====
    public void changeTitle(String v)        { if (v != null) this.title = v; }
    public void changeDescription(String v)  { this.description = v; }   // null 허용(설명 제거)
    public void changeCategory(String v)     { if (v != null) this.category = v; }
    public void changeRepeatDays(List<String> v) { if (v != null) this.repeatDays = new ArrayList<>(v); }
    public void changeVerificationMethods(List<String> v) { if (v != null) this.verificationMethods = new ArrayList<>(v); }
    public void changePenalty(PenaltyConfig v) { if (v != null) this.penalty = v; }
    public void changeReward(RewardConfig v)   { if (v != null) this.reward = v; }
    public void changeMinMannerTemperature(BigDecimal v) {
        if (v != null && participationType == ParticipationType.GROUP) this.minMannerTemperature = v;
    }

    /** durationDays/startDate 변경 시 endDate 재파생 (둘 중 하나만 와도 현재값과 합쳐 재계산) */
    public void changeSchedule(Integer durationDays, LocalDate startDate) {
        if (durationDays != null) this.durationDays = durationDays;
        if (startDate != null)    this.startDate = startDate;
        if (durationDays != null || startDate != null) {
            this.endDate = deriveEndDate(this.startDate, this.durationDays);
        }
    }

    // ===== 참여 인원 비정규화 캐시 =====
    public void increaseParticipantCount() { this.participantCount++; }
    public void decreaseParticipantCount() { if (this.participantCount > 0) this.participantCount--; }

    public void softDelete() { this.deletedAt = Instant.now(); }

    public boolean isOwner(UUID userId) { return this.creatorId.equals(userId); }
    public boolean isGroup() { return participationType == ParticipationType.GROUP; }
}